package com.github.cc11001100.weavergirl.sample.logcorrelation;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import org.slf4j.MDC;

/**
 * Wraps {@code com.example.support.TicketService.handleTicket} and bridges the active trace context
 * into the SLF4J MDC for the duration of the call.
 *
 * <p>This is the demo application's own plugin, and it exists to show the shape of a log
 * correlation plugin without depending on the built-in {@code logging} capability jar (whose
 * interceptor targets the logging backends' own logger classes). The application, {@code
 * TicketService}, knows nothing about it.
 *
 * <p>The bridge is symmetric: the previous MDC values are captured on entry and restored on exit
 * (and on exception), so a log line outside the call never inherits a stale trace id.
 */
public class LogCorrelationPlugin extends AbstractPlugin {

  private static final String TARGET = "com.example.support.TicketService";

  private static final String TRACE_ID = "traceId";
  private static final String SPAN_ID = "spanId";

  @Override
  public String name() {
    return "log-correlation-demo";
  }

  @Override
  public String instrumentedLibrary() {
    return TARGET;
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    registry.register(
        intercept(TARGET)
            .method("handleTicket")
            .around(
                inv -> {
                  // The engine mirrors its current span into ThreadContext for every intercepted
                  // call. Fall back to Tracer directly so a call made outside a propagated context
                  // still gets whatever span is current.
                  String traceId = ThreadContext.get(TRACE_ID);
                  String spanId = ThreadContext.get(SPAN_ID);
                  if (traceId == null && Tracer.hasCurrentSpan()) {
                    traceId = Tracer.getCurrentSpan().getTraceId();
                    spanId = Tracer.getCurrentSpan().getSpanId();
                  }
                  inv.setAttachment("log.previousTraceId", MDC.get(TRACE_ID));
                  inv.setAttachment("log.previousSpanId", MDC.get(SPAN_ID));
                  if (traceId != null) {
                    MDC.put(TRACE_ID, traceId);
                  }
                  if (spanId != null) {
                    MDC.put(SPAN_ID, spanId);
                  }
                },
                inv -> {
                  restore(inv);
                  InterceptorEventPublisher.getInstance()
                      .publish(
                          com.github.cc11001100.weavergirl.api.event.InterceptorEvent.builder()
                              .type("log-correlation")
                              .plugin(name())
                              .className(TARGET)
                              .methodName(inv.getMethodName())
                              .build());
                })
            .onException(
                inv -> {
                  restore(inv);
                })
            .priority(1)
            .build());
  }

  private static void restore(com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation inv) {
    Object previousTraceId = inv.getAttachment("log.previousTraceId");
    if (previousTraceId == null) {
      MDC.remove(TRACE_ID);
    } else {
      MDC.put(TRACE_ID, previousTraceId.toString());
    }
    Object previousSpanId = inv.getAttachment("log.previousSpanId");
    if (previousSpanId == null) {
      MDC.remove(SPAN_ID);
    } else {
      MDC.put(SPAN_ID, previousSpanId.toString());
    }
  }

  /** Kept for symmetry with the built-in plugin's builder API. */
  static InterceptorDefinition unused() {
    return null;
  }
}
