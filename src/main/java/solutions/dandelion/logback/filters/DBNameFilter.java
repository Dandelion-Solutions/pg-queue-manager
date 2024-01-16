package solutions.dandelion.logback.filters;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import ch.qos.logback.core.filter.Filter;

import java.util.HashSet;
import java.util.Set;

public class DBNameFilter extends Filter<ILoggingEvent> {
    private Set<String> patterns = new HashSet<>();

    @Override
    public FilterReply decide(ILoggingEvent event) {
        for (String pattern : patterns) {
            if (event.getLoggerName().contains(pattern)) return FilterReply.NEUTRAL;
        }
        return FilterReply.DENY;
    }

    public void addDbname(String dbname) {
        this.patterns.add("[dbname:".concat(dbname).concat("]"));
    }

}
