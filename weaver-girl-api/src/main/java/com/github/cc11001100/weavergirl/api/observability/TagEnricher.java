package com.github.cc11001100.weavergirl.api.observability;

import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import java.util.Map;

/**
 * Automatically enriches spans and metrics with application-specific tags.
 *
 * <p>Tag enrichers are invoked after the application code sets attributes on a span, before the span is
 * exported. This allows plugins to automatically add common tags like tenant ID, request ID, user ID, or
 * any other context that should appear on all observability signals without requiring manual annotation.
 *
 * <p>The enricher itself does not perform I/O or blocking operations. All context data reads should be
 * fast (e.g., thread-local or system property access).
 *
 * <p>Usage example:
 *
 * <pre>
 * public class TenantTagEnricher implements TagEnricher {
 *   @Override
 *   public String name() {
 *     return "tenant-enricher";
 *   }
 *
 *   @Override
 *   public Map&lt;String, String&gt; enrichTags(SpanContext span) {
 *     Map&lt;String, String&gt; tags = new LinkedHashMap&lt;&gt;();
 *     String tenantId = TenantContext.current();
 *     if (tenantId != null) {
 *       tags.put("tenant.id", tenantId);
 *     }
 *     String requestId = RequestIdContext.current();
 *     if (requestId != null) {
 *       tags.put("request.id", requestId);
 *     }
 *     return tags;
 *   }
 * }
 *
 * // Register as a SpanProcessor
 * context.registerSpanProcessor(new SpanProcessor() {
 *   @Override
 *   public String name() {
 *     return "tenant-enricher-processor";
 *   }
 *
 *   @Override
 *   public int priority() {
 *     return 100; // 在所有治理之后运行
 *   }
 *
 *   @Override
 *   public SpanContext process(SpanContext span) {
 *     Map&lt;String, String&gt; extraTags = enricher.enrichTags(span);
 *     if (extraTags.isEmpty()) {
 *       return span;
 *     }
 *     SpanContext.Builder builder = span.toBuilder();
 *     for (Map.Entry&lt;String, String&gt; entry : extraTags.entrySet()) {
 *       builder.attribute(entry.getKey(), entry.getValue());
 *     }
 *     return builder.build();
 *   }
 * });
 * </pre>
 *
 * <h3>Rules</h3>
 *
 * <ul>
 *   <li>Runs synchronously on the thread that ends the span. Keep it cheap and do not block.
 *   <li>Tags added here appear as span attributes and can be consumed by any downstream system.
 *   <li>Return an empty map to skip enrichment for this span.
 *   <li>Throwing from {@code enrichTags} is caught and the span is left unchanged.
 * </ul>
 *
 * @since 1.2.0
 */
public interface TagEnricher {

  /**
   * Stable name used for logging and metric labels.
   *
   * @return the enricher name, never null
   */
  String name();

  /**
   * Add tags to a span based on application context.
   *
   * <p>Called after the application sets attributes on the span, before it is exported. This allows plugins to
   * automatically add context like tenant ID, request ID, user ID, etc.
   *
   * <p>The span passed in already carries the attributes set by application code and by enrichers
   * that ran earlier, so an enricher can read a tag another one added. Tags returned here overwrite
   * an existing attribute with the same key.
   *
   * @param span the span about to be exported
   * @return a map of tags to add, or an empty map to skip enrichment
   */
  Map<String, String> enrichTags(SpanContext span);

  /**
   * Execution order among registered enrichers. Lower values run first.
   *
   * <p>Default is 0. Use a negative value for enrichers that establish a baseline tag other
   * enrichers read, and a positive value for enrichers that should see every other tag before
   * deciding.
   *
   * @return the priority value, default 0
   * @since 1.2.0
   */
  default int priority() {
    return 0;
  }
}
