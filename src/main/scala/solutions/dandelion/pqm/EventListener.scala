package solutions.dandelion.pqm

import java.sql.Connection

import solutions.dandelion.pqm.db.DBReportingManager.DBReporter
import com.mysterria.lioqu.commons.logging.LogHelpers._
import com.typesafe.scalalogging.Logger
import org.postgresql.PGNotification
import solutions.dandelion.pqm.db.{ConnectionManager, DBAwareRunnable, DBReportingManager}

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

/**
  * This class is responsible for listening for PG notifications. It manages single listener thread under the hood.
  * In case of any recoverable failure thread will be restarted after a short delay
  */
class EventListener[T](
    dbName: String,
    channel: String,
    eventConstructor: PGNotification => T,
    eventReporting: (DBReporter, Connection, T) => Unit,
    onEvent: T => Unit
) extends DBAwareLogging {
    protected val logger: Logger = logger(dbName)
    var thread: Thread = _
    var isStopped = false
    private val dbReporter = DBReportingManager.get(dbName)
    private val subscribedPromise = Promise[Unit]()

    /** Starts PG listener thread */
    def start(): Future[Unit] = {
        thread = new Thread(runnable(), "EventListener-thread")
        thread.start()
        subscribedPromise.future
    }

    /** Stops PG listener thread */
    def stop(): Unit = {
        isStopped = true
        thread.interrupt()
    }

    private def runnable(): Runnable = {
        new DBAwareRunnable(dbName) {
            override def runWithConnection(connection: Connection): Unit = {
                try {
                    subscribe(connection)
                    while (true) {
                        workCycle(connection)
                        Thread.sleep(AppConfig.ipcPGPollDelay)
                    }
                } catch {
                    case t: Throwable => handleInterrupt(t)
                }
            }

            override protected def handleError: PartialFunction[Throwable, Unit] = {
                case t: Throwable => handleInterrupt(t)
            }
        }
    }

    private def subscribe(connection: Connection): Unit = {
        val stmt = connection.createStatement()
        stmt.execute(s"LISTEN $channel")
        stmt.close()
        logger.info(s"Subscribed to $channel channel")
        subscribedPromise.trySuccess(())
    }

    private def handleInterrupt(t: Throwable): Unit = {
        if (!isStopped) {
            logger.error(log"Got exception while Listener was not in shutdown sequence. Will restart in 5 seconds.", t)
            Thread.sleep(AppConfig.ipcRestartDelay)
            start()
        } else {
            logger.info("EventListener stopped")
        }
    }

    private def workCycle(connection: Connection): Unit ={
        /* ATTENTION, WARNING, ACHTUNG! Do not provide pgconnection outside this method, _do not_ call pgconnection.close()! */
        val pgconnection = ConnectionManager.unwrapConnection(connection)

        // issue a dummy query to contact the backend
        // and receive any pending notifications.
        val stmt = connection.createStatement()
        val rs = stmt.executeQuery("SELECT 1")
        rs.close()
        stmt.close()

        // process notifications
        Option(pgconnection.getNotifications) foreach {
            _ foreach { notification =>
                logger.debug(log"Notification $notification has name ${notification.getName}, parameter ${notification.getParameter}, pid ${notification.getPID}")
                Try(eventConstructor(notification)) match {
                    case Success(event) =>
                        eventReporting(dbReporter, connection, event)
                        onEvent(event)
                    case Failure(t) =>
                        logger.error(log"Event construction error: Notification $notification has name ${notification.getName}, parameter ${notification.getParameter}, pid ${notification.getPID}", t)
                }
            }
        }
    }
}

