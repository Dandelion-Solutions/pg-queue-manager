package solutions.dandelion.pqm

import play.api.libs.json.JsObject

case class RoutineCronEvent(
    queue: String,
    routine: String,
    maxExecutionTime: Long,
    minExecutionTime: Long,
    args: JsObject
) extends VoidEvent with RoutineEvent