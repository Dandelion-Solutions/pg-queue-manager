package solutions.dandelion.pqm

import com.typesafe.scalalogging.LazyLogging
import org.quartz.{Job, Scheduler}
import org.quartz.spi.{JobFactory, TriggerFiredBundle}

class CronJobFactory(onEvent: RoutineEvent => Unit) extends JobFactory with LazyLogging {
    override def newJob(bundle: TriggerFiredBundle, scheduler: Scheduler): Job = {
        if (classOf[RoutineCronJob].isAssignableFrom(bundle.getJobDetail.getJobClass)) {
            val task = bundle.getTrigger.getJobDataMap.get("task").asInstanceOf[CronTask]
            new RoutineCronJob(onEvent, task)
        } else {
            throw new RuntimeException(s"Construction of jobs with class '${bundle.getJobDetail.getJobClass}' is unsupported")
        }
    }
}
