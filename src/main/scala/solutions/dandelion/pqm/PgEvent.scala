package solutions.dandelion.pqm

import org.postgresql.PGNotification

/**
  * Represents PG notification with additional unique ID given on receive
  *
  * @param notification original notification object (for logging purposes)
  */
class PgEvent[T](val notification: PGNotification, transform: String => T) extends Event[T]{
    def channel: String = notification.getName

    override val payload: T = transform(notification.getParameter)

    override def toString: String = {
        s"${getClass.getSimpleName}[id=$id;channel=$channel]"
    }
}

object PgEvent {
    def apply[T](notification: PGNotification, transform: String => T): PgEvent[T] = new PgEvent(notification, transform)
}
