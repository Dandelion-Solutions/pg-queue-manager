package solutions.dandelion.pqm.db

import java.sql._
import com.typesafe.scalalogging.Logger
import org.apache.commons.lang3.exception.ExceptionUtils
import solutions.dandelion.pqm.{AppConfig, DBAwareLogging, DatabaseConfig, Event, RoutineEvent, RoutinePgEvent}

import scala.util.Using

object DBReportingManager {
    val STATUS_CODE_INFO = "INFO"
    val STATUS_CODE_SUCCESS = "SUCCESS"
    val STATUS_CODE_ERROR = "ERROR"

    private val managers = AppConfig.databases.map { case (dbName, dbConfig) =>
        dbName -> DBReporter(dbName, dbConfig)
    }

    def get(dbName: String): DBReporter = managers(dbName)

    final case class DBReporter(dbName: String, dbConfig: DatabaseConfig) extends DBAwareLogging {
        protected val logger: Logger = logger(dbName)

        def reportGenericEventReceived(event: Event[_], connection: Connection): Unit ={
            reportAndHandleErrors(() => {
                Using(connection.prepareStatement(s"INSERT INTO ${dbConfig.dbTable_log} (event_code, guid, payload) VALUES(?,?,?)")) { stmt =>
                    var n = 1
                    stmt.setString(n, STATUS_CODE_INFO); n += 1
                    stmt.setObject(n, event.id, Types.OTHER); n += 1
                    stmt.setString(n, event.toString)
                    stmt.execute()
                }
            }, "Error occured on new generic event logging into DB")
        }

        def reportRoutinePgEventReceived(event: RoutinePgEvent, connection: Connection): Unit ={
            reportAndHandleErrors(() => {
                Using(connection.prepareStatement(s"INSERT INTO ${dbConfig.dbTable_log} (event_code, guid, queue_name, payload) VALUES(?,?,?,?)")) { stmt =>
                    var n = 1
                    stmt.setString(n, STATUS_CODE_INFO); n += 1
                    stmt.setObject(n, event.id, Types.OTHER); n += 1
                    stmt.setString(n, event.queue); n += 1
                    stmt.setString(n, event.payloadAsString)
                    stmt.execute()
                }
            }, "Error occured on new routine event logging into DB")
        }

        def reportEventProcessingError(event: RoutineEvent, throwable: Throwable): Unit ={
            Using(ConnectionManager.createConnection(dbName)) { connection =>
                reportEventProcessingError(event, throwable, connection)
            }
        }

        def reportEventProcessingError(event: RoutineEvent, throwable: Throwable, connection: Connection): Unit ={
            reportAndHandleErrors(() => {
                Using(connection.prepareStatement(s"INSERT INTO ${dbConfig.dbTable_log} (event_code, guid, queue_name, error, payload) VALUES(?,?,?,?,?)")) { stmt =>
                    var n = 1
                    stmt.setString(n, STATUS_CODE_ERROR); n += 1
                    stmt.setObject(n, event.id, Types.OTHER); n += 1
                    stmt.setString(n, event.queue); n += 1
                    stmt.setString(n, ExceptionUtils.getStackTrace(throwable)); n += 1
                    stmt.setString(n, event.toString)
                    stmt.execute()
                }
            }, "Error occured on error logging into DB")
        }

        def reportEventProcessingSuccess(event: RoutineEvent, result: String, executionTime: Long, connection: Connection): Unit = {
            reportAndHandleErrors(() => {
                Using(connection.prepareStatement(s"INSERT INTO ${dbConfig.dbTable_log} (event_code, guid, queue_name, error, query_exec_duration_ms) VALUES(?,?,?,?,?)")) { stmt =>
                    var n = 1
                    stmt.setString(n, STATUS_CODE_SUCCESS); n += 1
                    stmt.setObject(n, event.id, Types.OTHER); n += 1
                    stmt.setString(n, event.queue); n += 1
                    stmt.setString(n, result); n += 1
                    stmt.setInt(n, executionTime.toInt)
                    stmt.execute()
                }
            }, "Error occured on success logging into DB (yep, that's weird)")
        }

        /** Logs db error on db error or db error on db success. Don't use drugs. */
        def reportAndHandleErrors(op: () => Unit, msg: String): Unit = {
            try {
                op()
            } catch {
                case throwable: Throwable => logger.error(msg, throwable)
            }
        }
    }
}