package solutions.dandelion.pqm

object AppConfig {
    import com.typesafe.config.ConfigFactory

    private val conf =
        if (null == System.getProperty("config.file")) {
            throw new RuntimeException("Define a config via -Dconfig.file JVM parameter.")
        } else {
            ConfigFactory.load().getConfig("main")
        }

    val databases: Map[String, DatabaseConfig] = DatabaseConfig.parse(conf.getConfig("database"))

    val dbDriver = "org.postgresql.Driver"

    val defaultMaxExecutionTime: Long = conf.getLong("ipc.default_max_execution_time")
    val ipcRestartDelay = conf.getLong("ipc.restart_delay")
    val ipcPGPollDelay = conf.getLong("ipc.pg_poll_delay")
    val executorActivityTimeout = conf.getLong("ipc.executor_activity_timeout")
    val ipcDBPoolStatsDelay = conf.getLong("ipc.db_pool_stats_delay")

    val ReportLoggerName = "REPORT-LOGGER"
}
