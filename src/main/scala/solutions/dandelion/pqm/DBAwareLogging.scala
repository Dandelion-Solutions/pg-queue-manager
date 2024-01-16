package solutions.dandelion.pqm

import java.util.concurrent.atomic.AtomicReference

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

trait DBAwareLogging {
    private val loggerHolder = new AtomicReference[Logger](null)

    protected def logger(dbName: String): Logger = {
        loggerHolder.compareAndSet(null, Logger(LoggerFactory.getLogger(s"${getClass.getName}[dbname:$dbName]")))
        loggerHolder.get()
    }
}
