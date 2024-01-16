package solutions.dandelion.pqm

import java.sql.Connection

import solutions.dandelion.pqm.db.DBReportingManager.DBReporter
import org.postgresql.PGNotification
import RoutineEventListener._

class RoutineEventListener(dbName: String, channel: String, onEvent: RoutinePgEvent => Unit)
    extends EventListener[RoutinePgEvent] (dbName, channel, eventConstructor, reporting, onEvent)

object RoutineEventListener {
    private val eventConstructor: PGNotification => RoutinePgEvent = RoutinePgEvent.apply
    private val reporting: (DBReporter, Connection, RoutinePgEvent) => Unit = (reporter, connection, event) =>
        reporter.reportRoutinePgEventReceived(event, connection)

}
