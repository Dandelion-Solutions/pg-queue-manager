package solutions.dandelion.pqm

import com.typesafe.scalalogging.LazyLogging
import solutions.dandelion.pqm.db.ConnectionManager

import scala.language.postfixOps

/** Main and the only controller */
object Main extends App with LazyLogging{
    logger.info(s"Starting app ${BuildInfo.name} ${BuildInfo.version}")

    val brokers: Set[Broker] = AppConfig.databases map { case (dbName, dbConfig) =>
        new Broker(dbName, dbConfig)
    } toSet

    brokers.foreach(_.start())

    logger.info("Registering shutdown hook")
    sys.addShutdownHook({
        logger.info("ShutdownHook called")
        brokers.foreach(_.stop())
        ConnectionManager.stop()
    })

    logger.info(s"App ${BuildInfo.name} ${BuildInfo.version} started")
}
