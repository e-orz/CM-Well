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

import java.util.concurrent.{Executors, TimeUnit, TimeoutException}

import cmwell.driver.{Dao, DaoExecution}
import cmwell.fts.FTSServiceNew
import cmwell.util.concurrent.SimpleScheduler
import com.datastax.driver.core.{ConsistencyLevel, PreparedStatement, ResultSet}
import com.typesafe.scalalogging.Logger
import org.elasticsearch.action.bulk.{BulkRequestBuilder, BulkResponse}
import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.action.{ActionListener, ActionRequest}
import org.elasticsearch.action.update.UpdateRequest
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.netty.util.{HashedWheelTimer, Timeout, TimerTask}
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.index.VersionType

import scala.concurrent.duration.{Duration, DurationInt}
import org.rogach.scallop.ScallopConf

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

//scalastyle:off
object AddProtocolField extends StdInIterator with EsFutureHelpers {
  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    val host = opt[String]("host", required = true)
    val clusterName = opt[String]("cluster-name", required = true)
    val j = opt[Int]("j", required = true)
    val retryDelay = opt[Int]("retry-delay-millis", required = false, default = Some(500))
    val maxRetries = opt[Int]("max-retries", required = false, default = Some(3))
    verify()
  }

  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)
    implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(conf.j()))

    val retryDelay = conf.retryDelay().millis
    val maxRetries = conf.maxRetries()

    val esClient = {
      val cluster: String = conf.clusterName()
      val esSettings = ImmutableSettings
        .settingsBuilder()
        .put("cluster.name", cluster)
        .put("client.transport.sniff", true)
        .put("transport.netty.worker_count", 3)
        .put("transport.connections_per_node.recovery", 1)
        .put("transport.connections_per_node.bulk", 1)
        .put("transport.connections_per_node.reg", 2)
        .put("transport.connections_per_node.state", 1)
        .put("transport.connections_per_node.ping", 1)
        .build()
      val actualTransportAddress: String = conf.host()
      new TransportClient(esSettings)
        .addTransportAddress(new InetSocketTransportAddress(actualTransportAddress, 9301))
    }

    val dao = Dao(clusterName = "", "data2", conf.host(), conf.j())
    val insertExecutor = new CasExecutor(dao.getSession.prepare("INSERT INTO data2.infoton (uuid, quad, field, value) VALUES (?, 'cmwell://meta/sys', 'protocol', 'https');"))(dao)
    val selectExecutor = new CasExecutor(dao.getSession.prepare("SELECT value FROM data2.infoton WHERE uuid=? AND quad='cmwell://meta/sys' AND field='indexName';"))(dao)

    def getString(rs: ResultSet): String = rs.one().get(0, classOf[String])

    def esRequest(indexName: String, uuid: String) =
      esClient.prepareBulk().add(new UpdateRequest(indexName, "infoclone", uuid).doc(s"""{"system":{"protocol": "https"}}"""))


    def esClientExecWithRetries(uuid: String, request: BulkRequestBuilder, retry: Int = 1): Future[BulkResponse] = {
      injectFuture[BulkResponse](request.execute).flatMap {
        case br if !br.hasFailures => Future.successful(br)
        case _ if retry < maxRetries => SimpleScheduler.scheduleFuture(retryDelay) {
          esClientExecWithRetries(uuid, request, retry + 1)
        }
        case br => Future.failed(new RuntimeException(s"After $maxRetries retries: " + br.buildFailureMessage()))
      }
    }

    Console.err.println("\n\n >>> Executing...")
    iterateStdinInChunksShowingProgress { uuid =>
      insertExecutor.exec(uuid).zip(selectExecutor.exec(uuid).map(getString)).flatMap { case (_, indexName) =>
        val request = esRequest(indexName, uuid)
        esClientExecWithRetries(uuid, request)
      }.andThen {
        case Success(_) => println(s" >>> $uuid OK")
        case Failure(t) => println(s" >>> $uuid ERROR: $t")
      }
    }(conf.j())(ec).onComplete {
      case Failure(e) =>
        import java.io.PrintWriter
        import java.io.StringWriter
        val sw = new StringWriter
        val pw = new PrintWriter(sw)
        e.printStackTrace(pw)
        val sStackTrace = sw.toString
        Console.err.println(" >>> Finished with an error, closing connections in 16 seconds from now...\n")
        Console.err.println(s" >>> stack trace: $sStackTrace")
        Thread.sleep(16000)
        dao.shutdown()
        esClient.close()
        sys.exit(1)
      case Success(_) =>
        Console.err.println(" >>> Done, closing connections in 16 seconds from now...\n")
        Thread.sleep(16000)
        dao.shutdown()
        esClient.close()
        sys.exit(0)
    }
  }
}

object VerifyProtocolField extends StdInIterator with EsFutureHelpers {

  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    val host = opt[String]("host", required = true)
    val clusterName = opt[String]("cluster-name", required = true)
    val j = opt[Int]("j", required = true)
    verify()
  }

//  case class FetchedFields(indexName: String, protocol: Option[String])


  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)

    implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(conf.j()))

    val esClient = {
      val cluster: String = conf.clusterName()
      val esSettings = ImmutableSettings
        .settingsBuilder()
        .put("cluster.name", cluster)
        .put("client.transport.sniff", true)
        .put("transport.netty.worker_count", 3)
        .put("transport.connections_per_node.recovery", 1)
        .put("transport.connections_per_node.bulk", 1)
        .put("transport.connections_per_node.reg", 2)
        .put("transport.connections_per_node.state", 1)
        .put("transport.connections_per_node.ping", 1)
        .build()
      val actualTransportAddress: String = conf.host()
      new TransportClient(esSettings)
        .addTransportAddress(new InetSocketTransportAddress(actualTransportAddress, 9301))
    }

    val dao = Dao(clusterName = "", "data2", conf.host(), conf.j())

    val selectIndexNameExecutor = new CasExecutor(dao.getSession.prepare("SELECT value FROM data2.infoton WHERE uuid=? AND quad='cmwell://meta/sys' AND field='indexName';"))(dao)
    val selectProtocolExecutor = new CasExecutor(dao.getSession.prepare("SELECT value FROM data2.infoton WHERE uuid=? AND quad='cmwell://meta/sys' AND field='protocol';"))(dao)

    def getString(rs: ResultSet): String = rs.one().get(0, classOf[String])
    def getStringOpt(rs: ResultSet): Option[String] = if(rs.isExhausted) None else Option(rs.one().get(0, classOf[String]))

    def esRequest(indexName: String, uuid: String) = esClient.prepareGet(indexName, "infoclone", uuid)

    Console.err.println("\n\n >>> Executing...")
    iterateStdinInChunksShowingProgress { uuid =>

      selectIndexNameExecutor.exec(uuid).map(getString).zip(selectProtocolExecutor.exec(uuid).map(getStringOpt)).flatMap { case (indexName,protocolInCas) =>
        val request = esRequest(indexName, uuid)

        injectFuture[GetResponse](request.execute).map { esResp =>
          val protocolInEs = Option({
            val map = esResp.getSourceAsMap.get("system").asInstanceOf[java.util.Map[String,AnyRef]]
            if(map == null) null else map.get("protocol").asInstanceOf[String]
          })
          if(protocolInCas.getOrElse("") != "https" || protocolInEs.getOrElse("") != "https")
            println(s" >>> [VERIFICATION] $uuid $protocolInCas $protocolInEs")
        }.recover { case _ => println(s" >>> [VERIFICATION] $uuid FAILED") }
      }.recover { case _ => println(s" >>> [VERIFICATION] $uuid FAILED") }
    }(conf.j())(ec)

    Console.err.println(" >>> Done, waiting 1 minute before closing connections...\n")
    Thread.sleep(60000)
    dao.shutdown()
    esClient.close()
    sys.exit
  }
}

object FixType extends StdInIterator {
  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    val host = opt[String]("host", required = true)
    val j = opt[Int]("j", required = true)
    verify()
  }

  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)
    implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(conf.j()))
    val dao = Dao(clusterName = "", "data2", conf.host(), conf.j())
    val selectExecutor = new CasExecutor(dao.getSession.prepare("SELECT value FROM data2.infoton WHERE uuid=? AND quad='cmwell://meta/sys' AND field='type';"))(dao)
    val deleteExecutor = new CasExecutor(dao.getSession.prepare("DELETE FROM data2.infoton WHERE uuid=? AND quad='cmwell://meta/sys' AND field='type' AND value='o';"))(dao)

    def getValues(rs: ResultSet): Set[String] = {
      val lb = ListBuffer[String]()
      while(!rs.isExhausted) lb += rs.one().get("value", classOf[String])
      lb.result().toSet
    }

    val expected = Set("d","o")

    Console.err.println("\n\n >>> Executing...")
    iterateStdinShowingProgress { uuid =>
      selectExecutor.exec(uuid).map(getValues).flatMap { existingValues =>
        if(existingValues == expected) deleteExecutor.exec(uuid) else Future.successful(())
      }
    }(ec)

    Console.err.println(" >>> Done, waiting 1 minute before closing connections...\n")
    Thread.sleep(60000)
    dao.shutdown()
    sys.exit  }
}

trait StdInIterator {
  def iterateStdin(func: String => Any): Unit =
    scala.io.Source.stdin.getLines().foreach(func)

  def iterateStdinShowingProgress(func: String => Any)(implicit ec: ExecutionContext): Unit = {
    var c = 0
    val timer = SimpleScheduler.scheduleAtFixedRate(0.seconds, 500.millis) {
      Console.err.println(s" >>> $c items processed")
    }(ec)
    scala.io.Source.stdin.getLines().foreach { uuid =>
      c += 1
      func(uuid)
    }
    timer.cancel()
    Console.err.println(s" >>> $c items processed")
  }

  def iterateStdinInChunksShowingProgress(func: String => Future[_])(chunkSize: Int)(implicit ec: ExecutionContext): Future[_] = {
    var c = 0
    val timer = SimpleScheduler.scheduleAtFixedRate(0.seconds, 500.millis) {
      Console.err.println(s" >>> $c items processed")
    }(ec)

    val it = scala.io.Source.stdin.getLines().grouped(chunkSize)
    def processChunk: Future[_] =
      if(!it.hasNext) Future.successful(())
      else Future.sequence(it.next().map { uuid => c += 1; func(uuid) }).flatMap(_ => processChunk)

    processChunk.onComplete { _ =>
      timer.cancel()
      Console.err.println(s" >>> $c items processed - in onComplete")
    }
    processChunk
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
