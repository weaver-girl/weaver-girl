package com.github.cc11001100.weavergirl.api.event;

/**
 * Structured event emitted by interceptors.
 * Provides a programmatic way to consume interception data,
 * enabling integration with metrics systems, trace exporters,
 * and structured logging pipelines.
 *
 * <p>Events are created by plugins and dispatched via
 * {@link InterceptorEventPublisher}. Consumers register via
 * {@link InterceptorEventListener}.</p>
 *
 * <p>Example:</p>
 * <pre>
 * InterceptorEvent event = InterceptorEvent.builder()
 *     .type("slow-query")
 *     .plugin("jdbc")
 *     .class("PgStatement")
 *     .method("execute")
 *     .durationMs(2500)
 *     .attribute("sql", "SELECT * FROM users WHERE id = ?")
 *     .build();
 * </pre>
 */
public class InterceptorEvent {

    private final String type;
    private final String plugin;
    private final String className;
    private final String methodName;
    private final long timestamp;
    private final long durationMs;
    private final java.util.Map<String, String> attributes;

    private InterceptorEvent(String type, String plugin, String className,
                              String methodName, long timestamp, long durationMs,
                              java.util.Map<String, String> attributes) {
        this.type = type;
        this.plugin = plugin;
        this.className = className;
        this.methodName = methodName;
        this.timestamp = timestamp;
        this.durationMs = durationMs;
        this.attributes = attributes != null ? attributes : new java.util.LinkedHashMap<String, String>();
    }

    /** Returns the event type (e.g., {@code "slow-query"}, {@code "http-request"}). */
    public String getType() { return type; }
    /** Returns the name of the plugin that emitted this event. */
    public String getPlugin() { return plugin; }
    /** Returns the fully-qualified name of the intercepted class. */
    public String getClassName() { return className; }
    /** Returns the name of the intercepted method. */
    public String getMethodName() { return methodName; }
    /** Returns the epoch-millis timestamp when this event was created. */
    public long getTimestamp() { return timestamp; }
    /** Returns the measured duration in milliseconds, or 0 if not applicable. */
    public long getDurationMs() { return durationMs; }
    /** Returns an unmodifiable view of the event's key-value attributes. */
    public java.util.Map<String, String> getAttributes() { return java.util.Collections.unmodifiableMap(attributes); }

    /**
     * Convert to a JSON-friendly map for structured output.
     */
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("type", type);
        map.put("plugin", plugin);
        map.put("class", className);
        map.put("method", methodName);
        map.put("timestamp", timestamp);
        map.put("durationMs", durationMs);
        if (!attributes.isEmpty()) {
            map.put("attributes", attributes);
        }
        return map;
    }

    /**
     * Create a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for constructing {@link InterceptorEvent} instances.
     */
    public static class Builder {
        private String type;
        private String plugin;
        private String className;
        private String methodName;
        private long timestamp = System.currentTimeMillis();
        private long durationMs = 0;
        private final java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();

        /** Sets the event type (e.g., {@code "slow-query"}). */
        public Builder type(String type) { this.type = type; return this; }
        /** Sets the originating plugin name. */
        public Builder plugin(String plugin) { this.plugin = plugin; return this; }
        /** Sets the intercepted class name. */
        public Builder className(String className) { this.className = className; return this; }
        /** Sets the intercepted method name. */
        public Builder methodName(String methodName) { this.methodName = methodName; return this; }
        /** Sets the event timestamp (epoch millis). Defaults to current time. */
        public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }
        /** Sets the measured duration in milliseconds. */
        public Builder durationMs(long durationMs) { this.durationMs = durationMs; return this; }
        /** Adds a key-value attribute to the event. */
        public Builder attribute(String key, String value) { this.attributes.put(key, value); return this; }

        /** Builds an immutable {@link InterceptorEvent} from the configured values. */
        public InterceptorEvent build() {
            return new InterceptorEvent(type, plugin, className, methodName,
                    timestamp, durationMs, attributes);
        }
    }
}