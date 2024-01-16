package solutions.dandelion.pqm

import java.util.UUID

abstract class Event[T] (val id: UUID = Event.newId){
    def payload: T

    override def toString: String = {
        s"${getClass.getSimpleName}[id=$id]"
    }

    def payloadAsString: String = payload.toString
}

object Event {
    def newId: UUID = UUID.randomUUID
}
