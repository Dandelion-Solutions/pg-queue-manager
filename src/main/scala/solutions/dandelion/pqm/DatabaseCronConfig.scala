package solutions.dandelion.pqm

case class DatabaseCronConfig(
    dbCron_crontab: String,
    dbCron_channel: String
)

object DatabaseCronConfig {
    val CronKey = "cron"
}
