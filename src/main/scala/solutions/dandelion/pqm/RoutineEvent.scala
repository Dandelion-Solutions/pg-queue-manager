package solutions.dandelion.pqm

import play.api.libs.json.JsObject

trait RoutineEvent extends IdentifiedEvent {
    def queue: String
    def routine: String
    def maxExecutionTime: Long
    def minExecutionTime: Long
    def args: JsObject

    def hasArgs: Boolean = null != args && args.fields.nonEmpty


    def maxExecutionTimeSecs: Int = {
        val timeout = (maxExecutionTime / 1000).toInt
        if (timeout == 0) 1 else timeout
    }

    override def toString: String = {
        s"${getClass.getSimpleName}[id=$id;queue=$queue;routine=$routine;args=$args;maxExecutionTime=$maxExecutionTime;minExecutionTime=$minExecutionTime]"
    }
}
