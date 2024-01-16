package solutions.dandelion.pqm.db

import java.sql.Connection
import ConnectionManager.createConnection
import com.typesafe.scalalogging.Logger
import solutions.dandelion.pqm.DBAwareLogging

import scala.util.Using

trait DBAware extends DBAwareLogging {
    val dbName: String
    protected val logger: Logger = logger(dbName)
    def withConnection[T](op: Connection => T): T = Using(createConnection(dbName))(op).get
}
