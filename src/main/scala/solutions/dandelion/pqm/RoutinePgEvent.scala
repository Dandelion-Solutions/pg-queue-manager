package solutions.dandelion.pqm

import com.typesafe.scalalogging.LazyLogging
import org.postgresql.PGNotification
import play.api.libs.json._
import com.mysterria.lioqu.commons.logging.LogHelpers._

class RoutinePgEvent(notification: PGNotification) extends PgEvent(notification, Json.parse) with RoutineEvent with LazyLogging {
    val queue: String = (payload \ "queue").as[String]

    val routine: String = (payload \ "routine").as[String]

    val maxExecutionTime: Long = {
        (payload \ "max_exec_time").toOption match {
            case Some(JsNumber(v)) => v.toLong
            case _ =>
                logger.warn(log"Max Ececution Time (maxet) for event $this is not set, will use default ${AppConfig.defaultMaxExecutionTime}ms! Please set it explicitly to 0 if you want your routines to work forever.")
                AppConfig.defaultMaxExecutionTime
        }
    }

    val minExecutionTime: Long = {
        (payload \ "min_exec_time").toOption match {
            case Some(JsNumber(v)) => v.toLong
            case _ => -1L
        }
    }

    val args: JsObject = {
        payload \ "args" match {
            case v: JsDefined => v.get.as[JsObject]
            case _ => null
        }
    }

    override def toString: String = {
        s"Event[id=$id;channel=$channel;queue=$queue;routine=$routine;args=$args]"
    }

    override def payloadAsString: String = Json.stringify(payload)
}

object RoutinePgEvent {
    def apply(notification: PGNotification): RoutinePgEvent = new RoutinePgEvent(notification)
}