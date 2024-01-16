package solutions.dandelion.pqm

import org.postgresql.PGNotification

class VoidPgEvent(notification: PGNotification) extends PgEvent[Unit](notification, _ => ()) {
    override def payloadAsString: String = ""
}
