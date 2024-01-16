package solutions.dandelion.pqm

import java.util.concurrent.atomic.AtomicReference

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

trait DBAwareReportLogging {
    private val loggerHolder = new AtomicReference[Logger](null)

    protected def reportLoggerFactory(dbName: String): Logger = {
        loggerHolder.compareAndSet(null, Logger(LoggerFactory.getLogger(s"${AppConfig.ReportLoggerName}[dbname:$dbName]")))
        loggerHolder.get()
    }
}
