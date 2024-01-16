package solutions.dandelion.pqm

import play.api.libs.json.JsObject

case class CronTask(
    name: String,
    expression: String,
    routine: String,
    queue: String,
    args: JsObject,
    maxExecutionTime: Long,
    minExecutionTime: Long
)
