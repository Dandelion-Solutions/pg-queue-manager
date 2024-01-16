package solutions.dandelion.pqm

class VoidEvent extends Event[Unit] {
    override def payload: Unit = ()
    override def payloadAsString: String = ""
}
