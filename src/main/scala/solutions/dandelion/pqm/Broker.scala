package solutions.dandelion.pqm

import java.util.concurrent.{Executors, ThreadFactory, TimeUnit}
import com.mysterria.lioqu.commons.logging.LogHelpers._
import com.typesafe.scalalogging.Logger
import solutions.dandelion.pqm.action.Action
import solutions.dandelion.pqm.db.{ConnectionManager, DBReportingManager, ReportRunnable}

/** This is MODEL class, interact with application via public methods of this class */
class Broker(dbName: String, dbConfig: DatabaseConfig) extends DBAwareLogging {
    protected val logger: Logger = logger(dbName)

    private val executors = new ExecutorPool
    private val dbReportingManager = DBReportingManager.get(dbName)
    private val eventListener = new RoutineEventListener(dbName, dbConfig.dbChannel, routeEvent)

    private val dbCron = dbConfig.dbCron map { dbCronConfig =>
        logger.info(s"Starting DatabaseCron for $dbName")
        new DatabaseCron(dbName, dbCronConfig.dbCron_crontab, dbCronConfig.dbCron_channel, routeEvent)
    }

    @volatile
    private var isStopped = false

    def start(): Unit = {
        logger.info("Starting broker")
        eventListener.start()
        dbCron.foreach(_.start())
        scheduleReporting()
    }

    def stop(): Unit = {
        this.synchronized {
            if (isStopped) return
            eventListener.stop()
            dbCron.foreach(_.stop())
            isStopped = true // Events received before this line  while listener was shutting down still have a chance
            logger.info("Stopping Executors")
            executors.stop()
            logger.info("Executors stopped. Broker stopped.")
        }
    }

    private def routeEvent(event: RoutineEvent): Unit = {
        if (!isStopped) {
            try {
                executors.submit(event.queue, Action(dbName, event))
                ConnectionManager.tuneConnectionPool()
            } catch {
                case throwable: Throwable => handleEventRoutingException(event, throwable)
            }
        }
    }

    private def handleEventRoutingException(event: RoutineEvent, throwable: Throwable): Unit ={
        logger.error(log"Failed to route event $event", throwable)
        dbReportingManager.reportEventProcessingError(event, throwable)
    }

    private def scheduleReporting(): Unit = {
        Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
            override def newThread(runnable: Runnable): Thread = {
                val t: Thread = new Thread(runnable, "Report scheduler")
                t.setDaemon(true)
                t
            }
        }).scheduleWithFixedDelay(new ReportRunnable(dbName), 1, 1, TimeUnit.HOURS)
    }
}
