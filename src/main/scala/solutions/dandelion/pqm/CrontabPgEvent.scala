package solutions.dandelion.pqm

import org.postgresql.PGNotification

class CrontabPgEvent(notification: PGNotification) extends VoidPgEvent(notification)

object CrontabPgEvent {
    def apply(n: PGNotification): CrontabPgEvent = new CrontabPgEvent(n)
}
