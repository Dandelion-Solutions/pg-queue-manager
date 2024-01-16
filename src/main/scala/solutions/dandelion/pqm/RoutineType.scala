package solutions.dandelion.pqm

object RoutineType extends Enumeration {
    type RoutineType = RoutineType.Value

    val Procedure: RoutineType = Value("p")
    val Function: RoutineType = Value("f")
}
