/**
  * Copyright 2015 Thomson Reuters
  *
  * Licensed under the Apache License, Version 2.0 (the “License”); you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *   http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
  * an “AS IS” BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  *
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package cmwell.operations.modifier

import java.util.concurrent.{TimeUnit, TimeoutException}

import cmwell.driver.{Dao, DaoExecution}
import cmwell.fts.FTSServiceNew
import cmwell.util.concurrent.SimpleScheduler
import com.datastax.driver.core.{ConsistencyLevel, PreparedStatement, ResultSet}
import com.typesafe.scalalogging.Logger
import org.elasticsearch.action.bulk.BulkResponse
import org.elasticsearch.action.{ActionListener, ActionRequest}
import org.elasticsearch.action.update.UpdateRequest
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.netty.util.{HashedWheelTimer, Timeout, TimerTask}
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.common.transport.InetSocketTransportAddress

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationInt}
import org.rogach.scallop.ScallopConf

import scala.concurrent.{ExecutionContext, Promise}

//scalastyle:off
trait ModifierOperation {
  type UUID = String

  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    val host = opt[String]("host", required = true)
    verify()
  }

  val statement: String
  val task: (ResultSet, UUID) => Unit = (_,_) => ()


  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)

    val dao = Dao(clusterName = "", "data2", conf.host())
    val pStmt = dao.getSession.prepare(statement)
    val executor = new CasExecutor(pStmt)(dao)

    var c = 0
    val timer = SimpleScheduler.scheduleAtFixedRate(0.second, 500.millis) { println(s" >>> $c items processed") }
    println("\n\n >>> Executing...")
    scala.io.Source.stdin.getLines().foreach { uuid =>
      executor.exec(uuid).map(task(_,uuid))
      c += 1
    }

    timer.cancel()
    println(" >>> Done, shutting down connection...\n")
    dao.shutdown()
  }
}

object AddProtocolField extends ModifierOperation {
  override val statement = "INSERT INTO data2.infoton (uuid, quad, field, value) VALUES (?, 'cmwell://meta/sys', 'protocol', 'https');"
}

object VerifyProtocolField extends ModifierOperation {
  override val statement = "SELECT value FROM data2.infoton WHERE uuid=? AND quad='cmwell://meta/sys' AND field='protocol';"
  override val task: (ResultSet, String) => Unit = (rs, uuid) =>
    if(rs.isExhausted || rs.one().getString("value")!="https") println(s"[OOPS] $uuid was not verified!")
}

class CasExecutor(pStatement: PreparedStatement)(implicit dao: Dao) extends DaoExecution {
  def exec(uuid: String) =
    executeAsyncInternal(pStatement.bind(uuid).setConsistencyLevel(ConsistencyLevel.QUORUM))
}

object AddProtocolFieldToEs {
  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    val host = opt[String]("host", required = true)
    val clusterName = opt[String]("cluster-name", required = true)
    verify()
  }

  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)

    val esClient = {
      val cluster: String = conf.clusterName()
      val esSettings = ImmutableSettings
        .settingsBuilder()
        .put("cluster.name", cluster)
        .put("client.transport.sniff", true)
        .build()
      val actualTransportAddress: String = conf.host()
      new TransportClient(esSettings)
        .addTransportAddress(new InetSocketTransportAddress(actualTransportAddress, 9301))
    }

    val dao = Dao(clusterName = "", "data2", conf.host())
    val pStmt = dao.getSession.prepare("SELECT value FROM data2.infoton WHERE uuid=? AND quad='cmwell://meta/sys' AND field='indexName';")
    val executor = new CasExecutor(pStmt)(dao)

    var c = 0
    val timer = SimpleScheduler.scheduleAtFixedRate(0.second, 500.millis) { println(s" >>> $c items processed") }
    println("\n\n >>> Executing...")
    scala.io.Source.stdin.getLines().foreach { uuid =>
      val indexNameFut = executor.exec(uuid).map(_.one().get(0, classOf[String]))

      indexNameFut.flatMap { indexName =>
        val request = esClient.prepareBulk().add(new UpdateRequest(indexName, "infoclone", uuid).doc(s"""{"system":{"protocol": "https"}}"""))
        injectFuture[BulkResponse](request.execute)
      }

      c += 1
    }

    timer.cancel()

    println(" >>> Done, closing connection in 16 seconds from now...\n")
    Thread.sleep(16000)
    esClient.close()
  }

  private def injectFuture[A](
                               f: ActionListener[A] => Unit,
                               timeout: Duration = Duration.Inf
                             )(implicit executionContext: ExecutionContext) = {
    val p = Promise[A]()
    val timestamp = System.currentTimeMillis()

    val actionListener: ActionListener[A] = {
      if (timeout.isFinite()) {
        val task = TimeoutScheduler.tryScheduleTimeout(p, timeout)
        new ActionListener[A] {
          def onFailure(t: Throwable): Unit = {
            task.cancel()
            if (!p.tryFailure(t))
              println(s"Exception from ElasticSearch (future timed out externally, response returned in [${System
                .currentTimeMillis() - timestamp}ms])", t)
          }

          def onResponse(res: A): Unit = {
            task.cancel()
            if (p.trySuccess(res))
              println(
                s"Response from ElasticSearch [took ${System.currentTimeMillis() - timestamp}ms]:\n${res.toString}"
              )
            else
              println(s"Response from ElasticSearch (future timed out externally, response returned in [${System
                .currentTimeMillis() - timestamp}ms])\n${res.toString}")
          }
        }
      } else
        new ActionListener[A] {
          def onFailure(t: Throwable): Unit = {
            println(
              s"Exception from ElasticSearch (no timeout, response returned in [${System.currentTimeMillis() - timestamp}ms])",
              t
            )
            p.failure(t)
          }

          def onResponse(res: A): Unit = {
            println(
              s"Response from ElasticSearch [took ${System.currentTimeMillis() - timestamp}ms]:\n${res.toString}"
            )
            p.success(res)
          }
        }
    }

    f(actionListener)
    p.future
  }

  object TimeoutScheduler {
    val timer = new HashedWheelTimer(10, TimeUnit.MILLISECONDS)

    def scheduleTimeout(promise: Promise[_], after: Duration) = {
      timer.newTimeout(
        new TimerTask {
          override def run(timeout: Timeout) = {
            promise.failure(new TimeoutException("Operation timed out after " + after.toMillis + " millis"))
          }
        },
        after.toNanos,
        TimeUnit.NANOSECONDS
      )
    }

    def tryScheduleTimeout[T](promise: Promise[T], after: Duration) = {
      timer.newTimeout(
        new TimerTask {
          override def run(timeout: Timeout) = {
            promise.tryFailure(new TimeoutException("Operation timed out after " + after.toMillis + " millis"))
          }
        },
        after.toNanos,
        TimeUnit.NANOSECONDS
      )
    }
  }
}
//scalastyle:on
