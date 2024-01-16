package solutions.dandelion.pqm

import java.util
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, TimeUnit}
import org.quartz.CronScheduleBuilder._
import org.quartz.JobBuilder._
import org.quartz.TriggerBuilder._
import org.quartz.impl.StdSchedulerFactory
import org.quartz.impl.matchers.GroupMatcher
import org.quartz.{JobDataMap, Scheduler, Trigger, TriggerKey}
import play.api.libs.json.{JsObject, Json}
import solutions.dandelion.pqm.db.DBAware
import thesis._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters._
import scala.collection.mutable
import scala.util.{Try, Using}

class DatabaseCron(val dbName: String, crontab: String, channel: String, onEvent: RoutineEvent => Unit) extends DBAware with DBAwareReportLogging
{
    @volatile private var stopped = true
    private val stopLock = new AnyRef
    private def running: Boolean = !stopped

    private val reportLogger = reportLoggerFactory(dbName)

    private val crontabListener = new CrontabEventListener(dbName, channel, _ => handleCrontabUpdate())
    private val scheduler = initScheduler()
    private val triggerGenerationHolder = new AtomicLong(0L)
    private val cronJobName = s"${classOf[RoutineCronJob].getSimpleName}-$dbName"
    private val routineJobDetails = newJob(classOf[RoutineCronJob]).withIdentity(cronJobName).storeDurably().build()
    scheduler.addJob(routineJobDetails, false)

    private val timer = Executors.newScheduledThreadPool(1)

    private def init(): Unit = {
        Try(notifySelf()).map(_ => timer.shutdown()).failed.foreach { t =>
            logger.error(s"Unable to send pg_notify to init $dbName cron. The attempt will be repeated.", t)
            timer.schedule(new Runnable {
                override def run(): Unit = init()
            }, 5, TimeUnit.MINUTES)
        }
    }

    private def notifySelf(): Unit = withConnection { connection =>
        logger.info(s"Sending init notify for dbCron of $dbName to channel $channel")
        Using(connection.prepareCall(s"{call pg_notify(?, '')}")) { stmt =>
            stmt.setString(1, channel)
            stmt.execute()
        }
    }

    private def handleCrontabUpdate(): Unit = stopLock.synchronized {
        if (running) {
            val newGen = triggerGenerationHolder.incrementAndGet()
            val oldGen = newGen - 1
            logger.info(s"Starting crontab update $oldGen -> $newGen")
            val oldGroupMatcher: GroupMatcher[TriggerKey] = GroupMatcher.groupEquals(triggerGroupName(oldGen))
            val newGroupMatcher: GroupMatcher[TriggerKey] = GroupMatcher.groupEquals(triggerGroupName(newGen))
            val allGroupsMatchers = Seq(oldGroupMatcher, newGroupMatcher)
            try {
                allGroupsMatchers foreach scheduler.pauseTriggers
                val oldTriggers = ifDef(oldGen > 0) {
                    val set = scheduler.getTriggerKeys(oldGroupMatcher)
                    con(new util.ArrayList[TriggerKey](set.size())) { list =>
                        list.addAll(set)
                    }
                }
                val tasks = loadCronTasks()
                logger.info(s"Cron tasks to be loaded ($oldGen -> $newGen):\n${tasks.mkString("\n")}")
                tasks.map(t => mkTrigger(t, newGen)).foreach(scheduler.scheduleJob)
                reportLogger.info(s"Cron tasks loaded ($oldGen -> $newGen):\n${tasks.mkString("\n")}")

                oldTriggers.foreach(scheduler.unscheduleJobs)
                logger.info(s"Finished crontab update $oldGen -> $newGen")
            } catch {
                case t: Throwable => logger.error(s"Can not create new scheduler $oldGen -> $newGen", t)
            } finally allGroupsMatchers foreach scheduler.resumeTriggers
        }
    }

    private def mkTrigger(task: CronTask, gen: Long): Trigger = {
        newTrigger()
            .withIdentity(triggerName(task.name), triggerGroupName(gen))
            .withSchedule(cronSchedule(task.expression))
            .forJob(routineJobDetails)
            .usingJobData(new JobDataMap(Map("task" -> task).asJava))
            .build()
    }

    private def loadCronTasks(): Set[CronTask] = withConnection { connection =>
        Using(connection.prepareStatement(s"select * from $crontab")) { stmt =>
            Using(stmt.executeQuery()) { rs =>
                val tasks = mutable.Set[CronTask]()
                while(rs.next()) {
                    val name = rs.getString("name")
                    val expression = rs.getString("expression")
                    val routine = rs.getString("routine")
                    val queue = rs.getString("queue")
                    val args = Json.parse(rs.getString("args")).as[JsObject]
                    val maxExecutionTime = rs.getLong("max_execution_time")
                    val minExecutionTime = rs.getLong("min_execution_time")
                    val task = CronTask(
                        name = name,
                        expression = expression,
                        routine = routine,
                        queue = queue,
                        args = args,
                        maxExecutionTime = maxExecutionTime match {
                            case 0L => AppConfig.defaultMaxExecutionTime
                            case _ => maxExecutionTime
                        },
                        minExecutionTime = minExecutionTime match {
                            case 0L => -1L
                            case _ => minExecutionTime
                        }
                    )
                    tasks += task
                }
                tasks.toSet
            }
        }.flatten.get
    }

    private def triggerGroupName(gen: Long): String = DatabaseCron.triggerGroupName(dbName, gen)
    private def triggerName(taskName: String): String = DatabaseCron.triggerName(dbName, taskName)

    def stop(): Unit = stopLock.synchronized {
        if (running) {
            crontabListener.stop()
            timer.shutdown()
            scheduler.shutdown()
            stopped = !stopped
        }
    }

    def start(): Unit = stopLock.synchronized {
        if (stopped) {
            scheduler.start()
            crontabListener.start() foreach { _ => init() }
            stopped = !stopped
        }
    }

    private def initScheduler(): Scheduler = {
        val schedulerProps = new Properties()
        schedulerProps.setProperty("org.quartz.scheduler.instanceName", s"scheduler-$dbName")
        schedulerProps.setProperty("org.quartz.threadPool.threadCount", "1")
        schedulerProps.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore")
        val schedulerFactory = new StdSchedulerFactory(schedulerProps)
        val scheduler = schedulerFactory.getScheduler
        scheduler.setJobFactory(new CronJobFactory(onEvent))
        scheduler
    }
}

object DatabaseCron {
    private def triggerGroupName(dbName: String, gen: Long) = s"triggers-$dbName-g$gen"
    private def triggerName(dbName: String, taskName: String) = s"trigger-$dbName-$taskName"
}
