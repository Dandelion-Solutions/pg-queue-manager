package solutions.dandelion.pqm.action

import solutions.dandelion.pqm.RoutineEvent

trait Action extends Runnable {
    val event: RoutineEvent
}

object Action {
    /** Returns action class (implementing Runnable interface) to process an event */
    def apply(dbName: String, event: RoutineEvent): Action = new PgRoutineRunAction(dbName, event)
}