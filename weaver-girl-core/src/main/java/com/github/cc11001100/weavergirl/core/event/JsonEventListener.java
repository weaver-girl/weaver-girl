package com.github.cc11001100.weavergirl.core.event;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Built-in listener that outputs interceptor events as structured JSON
 * to the SLF4J logger. Enable with agent argument: {@code jsonEvents=true}
 *
 * <p>Output format:</p>
 * <pre>
 * {"type":"slow-query","plugin":"jdbc","class":"PgStatement","method":"execute","timestamp":1717236000000,"durationMs":2500,"attributes":{"sql":"SELECT ..."}}
 * </pre>
 *
 * <p>JSON events are logged at INFO level to the logger named
 * {@code com.github.cc11001100.weavergirl.event}. Configure your
 * logging framework to route this logger to a separate file or
 * structured logging pipeline.</p>
 */
public class JsonEventListener implements InterceptorEventListener {

    private static final Logger log = LoggerFactory.getLogger("com.github.cc11001100.weavergirl.event");

    @Override
    public void onEvent(InterceptorEvent event) {
        if (log.isInfoEnabled()) {
            log.info(toJson(event));
        }
    }

    /**
     * Convert event to a simple JSON string.
     * Does not depend on any JSON library — uses manual string building
     * for zero-dependency operation.
     */
    String toJson(InterceptorEvent event) {
        StringBuilder sb = new StringBuilder("{");
        appendJsonKey(sb, "type", event.getType());
        appendJsonKey(sb, "plugin", event.getPlugin());
        appendJsonKey(sb, "class", event.getClassName());
        appendJsonKey(sb, "method", event.getMethodName());
        sb.append("\"timestamp\":").append(event.getTimestamp()).append(",");
        sb.append("\"durationMs\":").append(event.getDurationMs());

        Map<String, String> attrs = event.getAttributes();
        if (!attrs.isEmpty()) {
            sb.append(",\"attributes\":{");
            boolean first = true;
            for (Map.Entry<String, String> entry : attrs.entrySet()) {
                if (!first) sb.append(",");
                appendJsonKey(sb, entry.getKey(), entry.getValue());
                first = false;
            }
            sb.append("}");
        }

        sb.append("}");
        return sb.toString();
    }

    private void appendJsonKey(StringBuilder sb, String key, String value) {
        sb.append("\"").append(escapeJson(key)).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(escapeJson(value)).append("\"");
        }
        sb.append(",");
    }

    private String escapeJson(String s) {
        if (s == null) return null;
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
