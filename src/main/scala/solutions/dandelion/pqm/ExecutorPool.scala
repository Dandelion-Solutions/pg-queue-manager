package solutions.dandelion.pqm

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicLong

import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import scala.concurrent.duration._
import com.mysterria.lioqu.commons.logging.LogHelpers._

import scala.jdk.CollectionConverters._
import thesis._

class ExecutorPool extends LazyLogging {
    private val executors = new ConcurrentHashMap[String, ExecutorService]().asScala
    private val activity = new ConcurrentHashMap[String, Long]().asScala

    private val purgeEnabled = AppConfig.executorActivityTimeout > 0
    private def purgeThreshold = System.currentTimeMillis() - AppConfig.executorActivityTimeout
    private def timeToPurge = lastPurgeHolder.get() < purgeThreshold
    private val purgeLock = new AnyRef
    private val lastPurgeHolder = new AtomicLong(System.currentTimeMillis())
    private def withPurgeLock[T](op: => T): T = {
        if (purgeEnabled) purgeLock.synchronized(op) else op
    }

    def submit(queue: String, runnable: Runnable): Unit = {
        executor(queue).submit(runnable)
        if (purgeEnabled && timeToPurge) purgePool()
    }

    def executor(queue: String): ExecutorService = withPurgeLock {
        activity(queue) = System.currentTimeMillis()
        executors.computeIfAbsent(queue, createExecutor)
    }

    def stop(): Unit = {
        val futures = executors.map { case (queue, executorService) =>
            executorService.shutdown()
            Future { waitForExecutorShutdown(executorService, queue) }
        }
        Await.ready(Future.sequence(futures), 15 minutes)
    }

    def purgePool(): Int = withPurgeLock {
        val threshold = purgeThreshold
        activity.filter(_._2 < threshold).keys.foreach(purge)
        executors.size
    }

    private def purge(queue: String): Unit = {
        activity remove queue
        executors.remove(queue).foreach(_.shutdown())
        logger.info(log"Executor for $queue was purged for inactivity")
    }

    private def createExecutor(queue: String): ExecutorService = {
        Executors.newSingleThreadExecutor(new ThreadFactory {
            override def newThread(runnable: Runnable): Thread = {
                new Thread(new ThreadGroup("Queue-processors"), runnable, s"$queue queue-processor-thread")
            }
        })
    }

    private def waitForExecutorShutdown(executorService: ExecutorService, queue: String): ExecutorService = {
        while (!executorService.awaitTermination(1, TimeUnit.MINUTES)) {
            logger.info(log"Executor for $queue still has tasks")
        }
        logger.info(log"Executor for $queue stopped")
        executorService
    }
}
