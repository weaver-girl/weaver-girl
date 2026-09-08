package com.github.cc11001100.weavergirl.api.context;

import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import com.github.cc11001100.weavergirl.api.tracing.TracingSnapshot;

/**
 * Built-in {@link ContextPropagator} that propagates the current {@link Tracer} span across thread
 * boundaries.
 *
 * <p>In addition to restoring the span itself, this propagator mirrors {@code traceId} and {@code
 * spanId} into {@link ThreadContext} during {@link #restore}. This bridges the two data sources so
 * that downstream plugins reading {@code ThreadContext.get("traceId")} or {@code
 * ThreadContext.get("spanId")} see the propagated values without manual synchronization by the
 * application.
 *
 * <h3>Cleanup semantics</h3>
 *
 * <p>Cleanup restores the worker thread's <em>prior</em> span symmetrically (unlike the deprecated
 * {@code TraceRunnable} which calls {@link Tracer#clearCurrentSpan()}). When the worker had no
 * prior span, cleanup calls {@code clearCurrentSpan()} to avoid leaking the task's span into the
 * next task on the same worker thread.
 *
 * <h3>Tracer.restore(null) pitfall</h3>
 *
 * <p>Cleanup cannot delegate to {@link Tracer#restore} with a null/empty snapshot because {@code
 * Tracer.restore} is a no-op when the snapshot has no span — it would leave the task's span active
 * on the worker thread. Instead, cleanup explicitly checks whether the previous snapshot had a span
 * and either restores it or clears the current span.
 *
 * @see Tracer
 * @see ThreadContext
 * @since 1.7.0
 */
public class TracerPropagator implements ContextPropagator {

  static final class SpanSnapshot implements Snapshot {
    final TracingSnapshot tracingSnapshot;
    static final SpanSnapshot EMPTY = new SpanSnapshot(null);

    SpanSnapshot(TracingSnapshot tracingSnapshot) {
      this.tracingSnapshot = tracingSnapshot;
    }
  }

  @Override
  public String name() {
    return "tracer-span";
  }

  @Override
  public Snapshot capture() {
    return Tracer.hasCurrentSpan() ? new SpanSnapshot(Tracer.capture()) : SpanSnapshot.EMPTY;
  }

  @Override
  public void restore(Snapshot snapshot) {
    if (!(snapshot instanceof SpanSnapshot)) {
      return;
    }
    SpanSnapshot s = (SpanSnapshot) snapshot;
    if (s.tracingSnapshot != null && s.tracingSnapshot.hasSpan()) {
      Tracer.restore(s.tracingSnapshot);
      // Bridge: mirror traceId and spanId into ThreadContext so that
      // plugins reading ThreadContext.get("traceId"/"spanId") see the
      // propagated values. Cleanup will restore the worker's prior
      // ThreadContext (via ThreadContextPropagator), clearing these keys.
      SpanContext span = s.tracingSnapshot.getSpanContext();
      ThreadContext.put("traceId", span.getTraceId());
      ThreadContext.put("spanId", span.getSpanId());
    }
  }

  @Override
  public void cleanup(Snapshot previous) {
    if (!(previous instanceof SpanSnapshot)) {
      return;
    }
    SpanSnapshot p = (SpanSnapshot) previous;
    if (p.tracingSnapshot != null && p.tracingSnapshot.hasSpan()) {
      // Worker had a prior span — restore it symmetrically.
      Tracer.setCurrentSpan(p.tracingSnapshot.getSpanContext());
    } else {
      // Worker had no prior span — clear to avoid leaking the task's span.
      Tracer.clearCurrentSpan();
    }
    // Note: ThreadContext keys ("traceId"/"spanId") written by restore()
    // are cleaned up by ThreadContextPropagator.cleanup(), which restores
    // the worker's prior ThreadContext snapshot (that did not contain these
    // keys). So no explicit ThreadContext.remove() is needed here.
  }

  @Override
  public int priority() {
    // After ThreadContext (-200) so the bridge keys it writes into
    // ThreadContext are visible to lower-priority propagators that read
    // them (TenantContextPropagator, MdcPropagator).
    return -100;
  }
}
