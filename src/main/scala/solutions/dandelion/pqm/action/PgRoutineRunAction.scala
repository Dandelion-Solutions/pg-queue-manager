package solutions.dandelion.pqm.action

import java.sql.{Connection, PreparedStatement, Statement, Types}
import solutions.dandelion.pqm.RoutineType.RoutineType
import com.mysterria.lioqu.commons.logging.LogHelpers._
import play.api.libs.json._
import solutions.dandelion.pqm.db.DBAwareRunnable
import solutions.dandelion.pqm.{AppConfig, RoutineEvent, RoutineType}

import scala.util.Using

class PgRoutineRunAction(dbName: String, val event: RoutineEvent) extends DBAwareRunnable(dbName) with Action {
    val pgParamPlaceholder = " := ?"

    override def runWithConnection(connection: Connection): Unit = {
        try {
            logger.info(log"Running action on event $event, payload is ${event.args}")
            val result: ExecutionResult = Using(prepareCall(connection, event)) { stmt =>
                if (event.maxExecutionTime > 0 ) stmt.setQueryTimeout(event.maxExecutionTimeSecs)
                ExecutionResult(time = time(stmt.execute()), result = fetchResult(stmt))
            }.get
            if (result.time < event.minExecutionTime)
                logger.warn(log"Execution time for event $event was less then minimal (${result.time} < ${event.minExecutionTime})")
            dbReportingManager.reportEventProcessingSuccess(event, result.result, result.time, connection)
        } catch {
            case throwable: Throwable => handleException(throwable, connection)
        }
    }

    private def time(executionUnit: => Unit): Long = {
        val start: Long = System.currentTimeMillis()
        executionUnit
        System.currentTimeMillis() - start
    }

    private def fetchResult(stmt: Statement): String = {
        val resultSetUnsafe = stmt.getResultSet
        if (null != resultSetUnsafe) {
            Using(resultSetUnsafe) { rs =>
                val sb = new StringBuilder
                while (rs.next()) {
                    sb.append(rs.getString(1))
                }
                sb.toString()
            }.get
        } else ""
    }

    private def handleException(throwable: Throwable, connection: Connection): Unit = {
        logger.error(log"Exception on routine call: $event", throwable)
        dbReportingManager.reportEventProcessingError(event, throwable, connection)
    }

    private def discoverRoutineType(connection: Connection, routineName: String): RoutineType = {
        AppConfig.databases(dbName).routineTypeDiscoveryFunc map { routineTypeDiscoveryFuncName =>
            try {
                Using(connection.prepareCall(s"{ call $routineTypeDiscoveryFuncName(?) }")) { stmt =>
                    stmt.setString(1, routineName)
                    stmt.execute()
                    Using(stmt.getResultSet) { rs =>
                        if (rs.next()) Some(rs.getString(1)) else None
                    }.get
                }.get map RoutineType.withName getOrElse RoutineType.Function
            } catch {
                case t: Throwable =>
                    logger.error(s"Unable to determine type of routine: $routineName", t)
                    RoutineType.Function
            }
        } getOrElse RoutineType.Function
    }

    private def prepareCall(connection: Connection, event: RoutineEvent): PreparedStatement = {
        val routineType = discoverRoutineType(connection, event.routine)

        if (event.hasArgs) {
            val fields = event.args.fields
            val sql = if (RoutineType.Procedure == routineType) {
                s"call ${event.routine}(${buildSignature(fields)})"
            } else {
                s"{ call ${event.routine}(${buildSignature(fields)}) }"
            }
            logger.debug(log"Event $event SQL: $sql")
            val stmt = connection.prepareCall(sql)
            propagateParams(stmt, fields)
            stmt
        } else {
            if (RoutineType.Procedure == routineType) {
                connection.prepareCall(s"call ${event.routine}()")
            } else {
                connection.prepareCall(s"{ call ${event.routine}() }")
            }
        }
    }

    private def propagateParams(stmt: PreparedStatement, fields: collection.Seq[(String, JsValue)]): Unit = {
        for(i <- fields.indices; value = fields(i)._2) {
            value match {
                case n:JsNumber if n.value.scale > 0    => stmt.setBigDecimal(i+1, n.value.bigDecimal)
                case n:JsNumber             => stmt.setLong(i+1, n.value.longValue)
                case _:JsObject | _:JsArray => stmt.setObject(i+1, value, Types.OTHER)
                case _:JsBoolean            => stmt.setBoolean(i+1, value.as[Boolean])
                case JsNull                 => stmt.setNull(i+1, Types.NULL)
                case _                      => stmt.setString(i+1, value.as[String])
            }
        }
    }

    private def buildSignature(fields: collection.Seq[(String, JsValue)]): String = {
        if (fields.nonEmpty) {
            val sigLength = fields.map { case (name, _) => name.length + pgParamPlaceholder.length }.sum + (fields.size - 1) * 2
            val sb = new StringBuilder(sigLength)
            for (i <- fields.indices) {
                sb.append(fields(i)._1)
                sb.append(pgParamPlaceholder)
                if (i < fields.indices.last) sb.append(", ")
            }
            sb.toString()
        } else ""
    }

    private case class ExecutionResult(time: Long, result: String)
}
