package solutions.dandelion.pqm.db

import java.sql.Connection
import com.typesafe.scalalogging.Logger
import solutions.dandelion.pqm.{AppConfig, DBAwareLogging}

import scala.util.Using

abstract class DBAwareRunnable(dbName: String) extends Runnable with DBAwareLogging {
    protected val logger: Logger = logger(dbName)

    protected def dbReportingManager: DBReportingManager.DBReporter = DBReportingManager.get(dbName)

    protected val dbConfig = AppConfig.databases(dbName)

    override def run(): Unit = {
        try {
            Using(ConnectionManager.createConnection(dbName)) { connection =>
                runWithConnection(connection)
            }
        } catch handleError
    }
    def runWithConnection(c: Connection): Unit

    protected def handleError: PartialFunction[Throwable, Unit] = {
        case t:Throwable => logger.error(s"Uncaught error", t)
    }
}
