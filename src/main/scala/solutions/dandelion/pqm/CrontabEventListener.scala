package solutions.dandelion.pqm

class CrontabEventListener(dbName: String, channel: String, onEvent: CrontabPgEvent => Unit)
    extends EventListener[CrontabPgEvent](
        dbName,
        channel,
        CrontabPgEvent.apply,
        (reporter, connection, event) => reporter.reportGenericEventReceived(event, connection),
        onEvent
    )
