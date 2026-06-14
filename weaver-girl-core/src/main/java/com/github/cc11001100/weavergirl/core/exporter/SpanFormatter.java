package com.github.cc11001100.weavergirl.core.exporter;

import java.util.List;

/**
 * Interface for formatting and delivering span data to external systems.
 */
public interface SpanFormatter {

    /**
     * Export a batch of spans.
     *
     * @param spans unmodifiable list of span data
     */
    void export(List<SpanData> spans);

    /**
     * Logging formatter — outputs OTLP JSON to SLF4J.
     */
    class LoggingSpanFormatter implements SpanFormatter {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LoggingSpanFormatter.class);

        @Override
        public void export(List<SpanData> spans) {
            for (SpanData span : spans) {
                log.info("[Span] {}", span.toOtlpJson());
            }
        }
    }

    /**
     * In-memory formatter — stores spans in a bounded list for testing.
     */
    class InMemorySpanFormatter implements SpanFormatter {
        private final java.util.List<SpanData> stored;
        private final int maxSize;

        public InMemorySpanFormatter(int maxSize) {
            this.maxSize = maxSize;
            this.stored = new java.util.ArrayList<>();
        }

        public InMemorySpanFormatter() {
            this(1000);
        }

        @Override
        public void export(List<SpanData> spans) {
            for (SpanData span : spans) {
                if (stored.size() < maxSize) {
                    stored.add(span);
                }
            }
        }

        public List<SpanData> getSpans() { return java.util.Collections.unmodifiableList(stored); }
        public int size() { return stored.size(); }
        public void clear() { stored.clear(); }
    }
}
