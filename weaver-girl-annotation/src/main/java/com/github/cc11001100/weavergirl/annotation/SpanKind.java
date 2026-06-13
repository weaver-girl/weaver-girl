package com.github.cc11001100.weavergirl.annotation;

/**
 * Span kind enumeration for {@link Trace} annotation.
 *
 * @since 1.5.0
 */
public enum SpanKind {
    /** Default span kind — no particular role. */
    INTERNAL,
    /** Server-side span — handles incoming requests. */
    SERVER,
    /** Client-side span — makes outgoing requests. */
    CLIENT,
    /** Producer span — sends messages to a broker. */
    PRODUCER,
    /** Consumer span — receives messages from a broker. */
    CONSUMER
}
