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

import cmwell.driver.{Dao, DaoExecution}
import cmwell.fts.FTSServiceNew
import cmwell.util.concurrent.SimpleScheduler
import com.datastax.driver.core.{ConsistencyLevel, PreparedStatement, ResultSet}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import org.rogach.scallop.ScallopConf

//scalastyle:off
trait ModifierOperation {
  type UUID = String
  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) { val host = opt[String]("host", required = true); verify() }



  val statement: String
  val task: (ResultSet, UUID) => Unit = (_,_) => ()



  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)


    val fts = FTSServiceNew()

    val dao = Dao(clusterName = "", "data2", conf.host())
    val pStmt = dao.getSession.prepare(statement)
    val executor = new Executor(pStmt)(dao)

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

class Executor(pStatement: PreparedStatement)(implicit dao: Dao) extends DaoExecution {
  def exec(uuid: String) =
    executeAsyncInternal(pStatement.bind(uuid).setConsistencyLevel(ConsistencyLevel.QUORUM))
}
//scalastyle:on
