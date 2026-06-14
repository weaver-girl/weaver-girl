package com.github.cc11001100.weavergirl.core.logging;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured logger for the weaver-girl agent.
 *
 * <p>Provides two output formats:</p>
 * <ul>
 *   <li><strong>TEXT</strong> — human-readable log lines with consistent prefix</li>
 *   <li><strong>JSON</strong> — structured JSON log lines for machine parsing</li>
 * </ul>
 *
 * <p>The log format is controlled by the {@code logFormat} agent argument
 * ({@code logFormat=json} or {@code logFormat=text}, default: text).</p>
 *
 * <h3>Usage</h3>
 * <pre>
 * StructLogger log = StructLogger.getLogger("PluginLoader");
 * log.info("Plugin loaded").with("plugin", "jdbc").with("interceptors", 5).log();
 * log.warn("Plugin failed").with("plugin", "redis").with("error", "connection refused").log();
 * </pre>
 *
 * @since 1.0.0
 */
public final class StructLogger {

    /** Log format enum. */
    public enum Format { TEXT, JSON }

    private static volatile Format globalFormat = Format.TEXT;
    private static volatile PrintStream outputStream = System.err;

    private final String component;

    private StructLogger(String component) {
        this.component = component;
    }

    /**
     * Get a logger for the given component name.
     */
    public static StructLogger getLogger(String component) {
        return new StructLogger(component);
    }

    /**
     * Set the global log format.
     *
     * @param format "text" or "json"
     */
    public static void setGlobalFormat(String format) {
        if ("json".equalsIgnoreCase(format)) {
            globalFormat = Format.JSON;
        } else {
            globalFormat = Format.TEXT;
        }
    }

    /**
     * Get the current global format.
     */
    public static Format getGlobalFormat() {
        return globalFormat;
    }

    /**
     * Set the output stream for testing purposes.
     *
     * @param stream the output stream to write to
     */
    static void setOutputStream(PrintStream stream) {
        outputStream = stream;
    }

    /**
     * Start a new log entry at INFO level.
     */
    public LogEntry info(String message) {
        return new LogEntry(this, "INFO", message);
    }

    /**
     * Start a new log entry at WARN level.
     */
    public LogEntry warn(String message) {
        return new LogEntry(this, "WARN", message);
    }

    /**
     * Start a new log entry at ERROR level.
     */
    public LogEntry error(String message) {
        return new LogEntry(this, "ERROR", message);
    }

    /**
     * Start a new log entry at DEBUG level.
     */
    public LogEntry debug(String message) {
        return new LogEntry(this, "DEBUG", message);
    }

    void emit(LogEntry entry) {
        if (globalFormat == Format.JSON) {
            emitJson(entry);
        } else {
            emitText(entry);
        }
    }

    private void emitText(LogEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("[weaver-girl] ").append(entry.level);
        sb.append(" [").append(component).append("] ");
        sb.append(entry.message);
        if (!entry.fields.isEmpty()) {
            sb.append(" — ");
            boolean first = true;
            for (Map.Entry<String, Object> e : entry.fields.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
        }
        outputStream.println(sb.toString());
    }

    private void emitJson(LogEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"timestamp\":").append(System.currentTimeMillis());
        sb.append(",\"level\":\"").append(entry.level).append("\"");
        sb.append(",\"logger\":\"").append(escape(component)).append("\"");
        sb.append(",\"message\":\"").append(escape(entry.message)).append("\"");
        if (!entry.fields.isEmpty()) {
            sb.append(",\"context\":{");
            boolean first = true;
            for (Map.Entry<String, Object> e : entry.fields.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape(e.getKey())).append("\":");
                if (e.getValue() instanceof Number) {
                    sb.append(e.getValue());
                } else {
                    sb.append("\"").append(escape(String.valueOf(e.getValue()))).append("\"");
                }
            }
            sb.append("}");
        }
        sb.append("}");
        outputStream.println(sb.toString());
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /**
     * A structured log entry being built.
     */
    public static class LogEntry {
        private final StructLogger logger;
        private final String level;
        private final String message;
        private final Map<String, Object> fields = new LinkedHashMap<>();

        LogEntry(StructLogger logger, String level, String message) {
            this.logger = logger;
            this.level = level;
            this.message = message;
        }

        /** Add a key-value pair to this log entry. */
        public LogEntry with(String key, Object value) {
            fields.put(key, value);
            return this;
        }

        /** Emit this log entry. */
        public void log() {
            logger.emit(this);
        }
    }
}
