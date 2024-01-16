package solutions.dandelion.pqm.db

import java.sql._
import com.typesafe.scalalogging.LazyLogging

import javax.sql.DataSource
import org.apache.commons.dbcp2._
import org.postgresql.PGConnection
import solutions.dandelion.pqm.AppConfig

import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import scala.language.postfixOps

object ConnectionManager extends LazyLogging{
    private val dataSources: Map[String, DataSource] = AppConfig.databases map { case (dbName, dbConfig) =>
        val ds: BasicDataSource = new BasicDataSource()
        ds.setDriverClassName(AppConfig.dbDriver)
        ds.setUrl(dbConfig.dbUrl)
        ds.setUsername(dbConfig.dbUser)
        ds.setPassword(dbConfig.dbPassword)
        ds.setMaxTotal(dbConfig.dbMaxTotal)
        ds.setAccessToUnderlyingConnectionAllowed(true)

        // Eviction policy
        ds.setMaxConn(Duration.of(dbConfig.dbConnectionMaxLifeTime, ChronoUnit.MILLIS))
        ds.setMinIdle(0)
        ds.setMaxIdle(-1)
        ds.setValidationQuery("SELECT 1")
        ds.setTestOnBorrow(dbConfig.dbTestOnBorrow)
        ds.setFastFailValidation(dbConfig.dbFastFailValidation)
        ds.setDurationBetweenEvictionRuns(Duration.of(dbConfig.dbEvictionDelay, ChronoUnit.MILLIS))
        ds.setMinEvictableIdle(Duration.of(dbConfig.dbEvictionMinIdle, ChronoUnit.MILLIS))
        ds.setLogExpiredConnections(false) // We are expecting some of routines to be working longer than connection expire period. No errors should be logged about it.
        dbName -> ds
    }

    @volatile
    private var lastTune: Long = 0

    def createConnection(dbName: String): Connection = dataSources(dbName).getConnection

    def stop(): Unit = dataSources.values.foreach(_.asInstanceOf[BasicDataSource].close())

    def unwrapConnection(wrapped: Connection): PGConnection  = {
        wrapped.asInstanceOf[DelegatingConnection[Connection]].getInnermostDelegate.asInstanceOf[PGConnection]
    }

    def tuneConnectionPool(): Unit = {
        val now = System.currentTimeMillis()
        if (now - lastTune > AppConfig.ipcDBPoolStatsDelay) {
            dataSources.foreach(printDataSourceStats _ tupled)
            lastTune = now
        }
    }

    private def printDataSourceStats(dbName: String, dataSource: DataSource): Unit = {
        logger.info(
            s"""
               |DB pool state for $dbName:
               |  NumActive = ${dataSource.asInstanceOf[BasicDataSource].getNumActive}
               |  NumIdle   = ${dataSource.asInstanceOf[BasicDataSource].getNumIdle}
               |  MaxIdle   = ${dataSource.asInstanceOf[BasicDataSource].getMaxIdle}
             """.stripMargin)
    }
}

