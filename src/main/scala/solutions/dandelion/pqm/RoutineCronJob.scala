package solutions.dandelion.pqm

import org.quartz.{Job, JobExecutionContext, JobExecutionException}

class RoutineCronJob(onEvent: RoutineEvent => Unit, task: CronTask) extends Job {
    override def execute(context: JobExecutionContext): Unit = {
        try {
            onEvent(RoutineCronEvent(
                queue = task.queue,
                routine = task.routine,
                maxExecutionTime = task.maxExecutionTime,
                minExecutionTime = task.minExecutionTime,
                args = task.args
            ))
        } catch {
            case t: Throwable =>
                throw new JobExecutionException(s"Error while submitting cron-based Routine from task $task", t)
        }
    }
}
