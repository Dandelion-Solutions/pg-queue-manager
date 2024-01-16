package solutions.dandelion.pqm.db

import java.sql.Connection
import org.joda.time.DateTime
import solutions.dandelion.pqm.DBAwareReportLogging

import scala.util.Using

class ReportRunnable(dbName: String) extends DBAwareRunnable(dbName) with DBAwareReportLogging {
    private val select = s"SELECT queue_name, event_code, count(*) as executed, AVG(query_exec_duration_ms) as execution_time FROM ${dbConfig.dbTable_log} WHERE creation_date > ? AND event_code != 'INFO' GROUP BY 1, 2 ORDER BY 1, 2"
    private def interval = java.sql.Timestamp.from(new DateTime().minusDays(1).toDate.toInstant)
    private val reportLogger = reportLoggerFactory(dbName)

    override def runWithConnection(connection: Connection): Unit = {
        try {
            doWork(connection)
        } catch {
            case t: Throwable => logger.error("Error occured while forming hourly stats", t)
        }
    }

    private def doWork(connection: Connection): Unit = {
        Using(connection.prepareStatement(select)) { stmt =>
            stmt.setTimestamp(1, interval)
            Using(stmt.executeQuery()) { rs =>
                val sb = new StringBuilder(s"${ReportRunnable.ReportTitle}:\n")
                var nonEmpty = false
                while (rs.next()) {
                    nonEmpty = true
                    val queueName = rs.getString(1)
                    val code = rs.getString(2)
                    val count = rs.getInt(3)
                    val executionTime = rs.getDouble(4)

                    sb.append("Queue: ")
                    sb.append(queueName)
                    sb.append(", eventCode: ")
                    sb.append(code)
                    sb.append(", count: ")
                    sb.append(count)
                    sb.append(", executionTime(AVG): ")
                    sb.append(executionTime)
                    sb.append("\n")
                }
                if (!nonEmpty) sb.append("No Data")
                reportLogger.info(sb.toString())
            }
        }
    }
}

object ReportRunnable {
    val ReportTitle = "Hourly performance report"

}