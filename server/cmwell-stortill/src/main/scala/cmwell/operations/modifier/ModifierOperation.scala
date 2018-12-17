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
import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.action.{ActionListener, ActionRequest}
import org.elasticsearch.action.update.UpdateRequest
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.netty.util.{HashedWheelTimer, Timeout, TimerTask}
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.index.VersionType

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationInt}
import org.rogach.scallop.ScallopConf

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}

//scalastyle:off
object AddProtocolField extends StdInIterator with EsFutureHelpers {
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
    val insertExecutor = new CasExecutor(dao.getSession.prepare("INSERT INTO data2.infoton (uuid, quad, field, value) VALUES (?, 'cmwell://meta/sys', 'protocol', 'https');"))(dao)
    val selectExecutor = new CasExecutor(dao.getSession.prepare("SELECT value FROM data2.infoton WHERE uuid=? AND quad='cmwell://meta/sys' AND field='indexName';"))(dao)

    def getString(rs: ResultSet): String = rs.one().get(0, classOf[String])

    def esRequest(indexName: String, uuid: String) =
      esClient.prepareBulk().add(new UpdateRequest(indexName, "infoclone", uuid).doc(s"""{"system":{"protocol": "https"}}"""))


    println("\n\n >>> Executing...")
    iterateStdinShowingProgress { uuid =>
      insertExecutor.exec(uuid).zip(selectExecutor.exec(uuid).map(getString)).flatMap { case (_, indexName) =>
        val request = esRequest(indexName, uuid)
        injectFuture[BulkResponse](request.execute)
      }
    }
    println(" >>> Done, closing connections in 16 seconds from now...\n")
    Thread.sleep(16000)
    dao.shutdown()
    esClient.close()
  }
}

object VerifyProtocolField extends StdInIterator with EsFutureHelpers {

  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    val host = opt[String]("host", required = true)
    val clusterName = opt[String]("cluster-name", required = true)
    verify()
  }

  case class FetchedFields(indexName: String, protocol: Option[String])

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
    val selectExecutor = new CasExecutor(dao.getSession.prepare("SELECT field,value FROM data2.infoton WHERE uuid=? AND quad='cmwell://meta/sys';"))(dao)



    def getFields(rs: ResultSet): FetchedFields = {
      var indexName: String = ""
      var protocol: Option[String] = None

      while(!rs.isExhausted) {
        val row = rs.one()
        val field = row.get("field", classOf[String])
        if(field == "indexName") indexName = row.get("value", classOf[String])
        if(field == "protocol") protocol = Some(row.get("value", classOf[String]))
      }

      FetchedFields(indexName, protocol)
    }

    def esRequest(indexName: String, uuid: String) = esClient.prepareGet(indexName, "infoclone", uuid)

    println("\n\n >>> Executing...")
    iterateStdinShowingProgress { uuid =>
      selectExecutor.exec(uuid).map(getFields).flatMap { fetchedFields =>
        val protocolInCas = fetchedFields.protocol
        val request = esRequest(fetchedFields.indexName, uuid)
        injectFuture[GetResponse](request.execute).map { esResp =>
          val protocolInEs = Option({
            val map = esResp.getSourceAsMap.get("system").asInstanceOf[java.util.Map[String,AnyRef]]
            if(map == null) null else map.get("protocol").asInstanceOf[String]
          })
          if(protocolInCas.getOrElse("") != "https" || protocolInEs.getOrElse("") != "https")
            println(s" >>> [VERIFICATION] $uuid $protocolInCas $protocolInEs")
        }.recover { case _ => println(s" >>> [VERIFICATION] $uuid FAILED") }
      }.recover { case _ => println(s" >>> [VERIFICATION] $uuid FAILED") }
    }

    println(" >>> Done, waiting 1 minute before closing connections...\n")
    Thread.sleep(60000)
    dao.shutdown()
    esClient.close()
    sys.exit
  }
}

object FixType extends StdInIterator {
  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    val host = opt[String]("host", required = true)
    verify()
  }

  def main(args: Array[String]): Unit = {
    val dao = Dao(clusterName = "", "data2", new Conf(args).host())
    val selectExecutor = new CasExecutor(dao.getSession.prepare("SELECT value FROM data2.infoton WHERE uuid=? AND quad='cmwell://meta/sys' AND field='type';"))(dao)
    val deleteExecutor = new CasExecutor(dao.getSession.prepare("DELETE FROM data2.infoton WHERE uuid=? AND quad='cmwell://meta/sys' AND field='type' AND value='o';"))(dao)

    def getValues(rs: ResultSet): Set[String] = {
      val lb = ListBuffer[String]()
      while(!rs.isExhausted) lb += rs.one().get("value", classOf[String])
      lb.result().toSet
    }

    val expected = Set("d","o")

    println("\n\n >>> Executing...")
    iterateStdinShowingProgress { uuid =>
      selectExecutor.exec(uuid).map(getValues).flatMap { existingValues =>
        println(s" >>> [DEBUG] type: [${existingValues.mkString(",")}]")
        if(existingValues == expected) deleteExecutor.exec(uuid) else Future.successful(())
      }
    }

    println(" >>> Done, waiting 1 minute before closing connections...\n")
    Thread.sleep(60000)
    dao.shutdown()
    sys.exit  }
}

trait StdInIterator {
  def iterateStdin(func: String => Any): Unit =
    scala.io.Source.stdin.getLines().foreach(func)

  def iterateStdinShowingProgress(func: String => Any): Unit = {
    var c = 0
    val timer = SimpleScheduler.scheduleAtFixedRate(0.seconds, 500.millis) {
      println(s" >>> $c items processed")
    }
    scala.io.Source.stdin.getLines().foreach { uuid =>
      c += 1
      func(uuid)
    }
    timer.cancel()
    println(s" >>> $c items processed")
  }
}

class CasExecutor(pStatement: PreparedStatement)(implicit dao: Dao) extends DaoExecution {
  def exec(uuid: String) =
    executeAsyncInternal(pStatement.bind(uuid).setConsistencyLevel(ConsistencyLevel.QUORUM))
}

trait EsFutureHelpers {
  def injectFuture[A](
                               f: ActionListener[A] => Unit,
                               timeout: Duration = Duration.Inf
                             )(implicit executionContext: ExecutionContext) = {
    val p = Promise[A]()

    val actionListener: ActionListener[A] = {
      if (timeout.isFinite()) {
        val task = TimeoutScheduler.tryScheduleTimeout(p, timeout)
        new ActionListener[A] {
          def onFailure(t: Throwable): Unit = {
            task.cancel()
            p.tryFailure(t)
          }

          def onResponse(res: A): Unit = {
            task.cancel()
            p.trySuccess(res)
          }
        }
      } else
        new ActionListener[A] {
          def onFailure(t: Throwable): Unit = p.failure(t)
          def onResponse(res: A): Unit = p.success(res)
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
