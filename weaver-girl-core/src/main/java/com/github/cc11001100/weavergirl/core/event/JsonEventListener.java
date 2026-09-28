package com.github.cc11001100.weavergirl.core.event;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventListener;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in listener that outputs interceptor events as structured JSON to the SLF4J logger. Enable
 * with agent argument: {@code jsonEvents=true}
 *
 * <p>Output format:
 *
 * <pre>
 * {"type":"slow-query","plugin":"jdbc","class":"PgStatement","method":"execute",
 * "timestamp":1717236000000,"durationMs":2500,"attributes":{"sql":"SELECT ..."}}
 * </pre>
 *
 * <p>JSON events are logged at INFO level to the logger named {@code
 * com.github.cc11001100.weavergirl.event}. Configure your logging framework to route this logger to
 * a separate file or structured logging pipeline.
 */
public class JsonEventListener implements InterceptorEventListener {

  private static final Logger log =
      LoggerFactory.getLogger("com.github.cc11001100.weavergirl.event");

  @Override
  public void onEvent(InterceptorEvent event) {
    if (log.isInfoEnabled()) {
      log.info(toJson(event));
    }
  }

  /**
   * JSON text for one already-published event. The agent {@code /events} route uses this same
   * method so operators and log pipelines see one document.
   */
  public String toJson(InterceptorEvent event) {
    return render(event);
  }

  /** Shared renderer for the logger and the recent-event listing. */
  public static String render(InterceptorEvent event) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    appendField(sb, "type", event.getType());
    sb.append(",");
    appendField(sb, "plugin", event.getPlugin());
    sb.append(",");
    appendField(sb, "class", event.getClassName());
    sb.append(",");
    appendField(sb, "method", event.getMethodName());
    sb.append(",");
    sb.append("\"timestamp\":").append(event.getTimestamp());
    sb.append(",\"durationMs\":").append(event.getDurationMs());
    Map<String, String> attrs = event.getAttributes();
    if (attrs != null && !attrs.isEmpty()) {
      sb.append(",\"attributes\":{");
      boolean first = true;
      for (Map.Entry<String, String> entry : attrs.entrySet()) {
        if (!first) {
          sb.append(",");
        }
        first = false;
        appendField(sb, entry.getKey(), entry.getValue());
      }
      sb.append("}");
    }
    sb.append("}");
    return sb.toString();
  }

  private static void appendField(StringBuilder sb, String key, String value) {
    sb.append("\"").append(escapeJson(key)).append("\":");
    if (value == null) {
      sb.append("null");
    } else {
      sb.append("\"").append(escapeJson(value)).append("\"");
    }
  }

  private static String escapeJson(String s) {
    if (s == null) {
      return null;
    }
    StringBuilder out = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\':
          out.append("\\\\");
          break;
        case '"':
          out.append("\\\"");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (c < 0x20) {
            out.append(String.format("\\u%04x", Integer.valueOf(c)));
          } else {
            out.append(c);
          }
          break;
      }
    }
    return out.toString();
  }
}
