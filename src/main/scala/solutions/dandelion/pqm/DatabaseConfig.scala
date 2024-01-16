package solutions.dandelion.pqm

import scala.jdk.CollectionConverters._
import com.typesafe.config.{Config, ConfigFactory}

import scala.language.postfixOps
import thesis._

case class DatabaseConfig (
    dbUrl: String,
    dbUser: String,
    dbPassword: String,
    dbChannel: String,
    dbMaxTotal: Int,
    dbEvictionDelay: Long,
    dbEvictionMinIdle: Long,
    dbFastFailValidation: Boolean,
    dbTestOnBorrow: Boolean,
    dbConnectionMaxLifeTime: Long,
    dbTable_log: String,
    dbCron: Option[DatabaseCronConfig],
    routineTypeDiscoveryFunc: Option[String]
)

object DatabaseConfig {
    case object Keys {
        val Connections = "connections"
    }

    def parse(source: Config): Map[String, DatabaseConfig] = {
        val connections = source.getConfig(Keys.Connections)
        connections.root().keySet().asScala map { cn =>
            val cfg = ConfigFactory.empty()
              .withFallback(connections.getConfig(cn))
              .withFallback(source)
              .withoutPath(Keys.Connections)
            cn -> fromConfig(cfg)
        } toMap
    }

    private def fromConfig(conf: Config): DatabaseConfig = {
        import DatabaseCronConfig.CronKey

        val dbCronConfig = ifDef(conf.hasPath(CronKey)) {
            DatabaseCronConfig(
                dbCron_crontab = conf.getString(s"$CronKey.crontab"),
                dbCron_channel = conf.getString(s"$CronKey.channel")
            )
        }

        DatabaseConfig(
            dbUrl = conf.getString("url"),
            dbUser = conf.getString("user"),
            dbPassword = conf.getString("password"),
            dbChannel = conf.getString("channel"),
            dbMaxTotal = conf.getInt("max_connections"),
            dbEvictionDelay = conf.getLong("eviction_delay"),
            dbEvictionMinIdle = conf.getLong("eviction_min_idle"),
            dbFastFailValidation = conf.getBoolean("fast_fail_validation"),
            dbTestOnBorrow = conf.getBoolean("test_on_borrow"),
            dbConnectionMaxLifeTime = conf.getLong("connection_max_life_time"),
            dbTable_log = conf.getString("tables.log"),
            dbCron = dbCronConfig,
            routineTypeDiscoveryFunc = ifDef(conf.hasPath("routine_type_discovery_func")){
                conf.getString("routine_type_discovery_func")
            }
        )
    }
}
