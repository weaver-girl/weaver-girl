package com.github.cc11001100.weavergirl.api.observability;

import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of {@link TagEnricher}s and the hook that applies them to finished spans.
 *
 * <p>Enrichers are invoked by {@code Tracer.endSpan} after the application has set its attributes
 * and <em>before</em> the {@link com.github.cc11001100.weavergirl.api.tracing.SpanProcessors} chain,
 * so a governance processor can redact or drop a tag an enricher added — enrichment cannot smuggle
 * data past governance. Registration order is execution order, resolved by {@link
 * TagEnricher#priority()} ascending; enrichers with the same priority keep their registration order.
 *
 * <p>Each enricher is isolated: one that throws or returns null is skipped and counted in {@link
 * ExtensionPerformanceTracker} under {@link ExtensionPerformanceTracker#KIND_TAG_ENRICHER}, and the
 * span continues unchanged.
 *
 * <p>Register through {@link com.github.cc11001100.weavergirl.api.plugin.PluginContext#registerTagEnricher}
 * so the enricher is withdrawn when the plugin is unloaded; registering here directly is possible
 * but then the plugin has to unregister it itself.
 *
 * @see TagEnricher
 * @since 1.2.0
 */
public final class TagEnrichers {

  private static final List<Holder> holders = new CopyOnWriteArrayList<Holder>();

  private TagEnrichers() {}

  /**
   * Register an enricher. Registering the same instance twice is a no-op.
   *
   * @param enricher the enricher to add
   */
  public static void register(TagEnricher enricher) {
    if (enricher == null) {
      return;
    }
    synchronized (TagEnrichers.class) {
      for (Holder holder : holders) {
        if (holder.enricher == enricher) {
          return;
        }
      }
      holders.add(new Holder(enricher));
      holders.sort((a, b) -> Integer.compare(a.priority, b.priority));
    }
  }

  /**
   * Remove an enricher.
   *
   * @param enricher the enricher to remove
   */
  public static void unregister(TagEnricher enricher) {
    if (enricher == null) {
      return;
    }
    synchronized (TagEnrichers.class) {
      for (Holder holder : holders) {
        if (holder.enricher == enricher) {
          holders.remove(holder);
          return;
        }
      }
    }
  }

  /**
   * The registered enrichers, in execution order.
   *
   * @return an immutable list
   */
  public static List<TagEnricher> getEnrichers() {
    List<TagEnricher> enrichers = new ArrayList<TagEnricher>(holders.size());
    for (Holder holder : holders) {
      enrichers.add(holder.enricher);
    }
    return Collections.unmodifiableList(enrichers);
  }

  /** Whether any enricher is registered. Lets the caller avoid work when none is. */
  public static boolean isEmpty() {
    return holders.isEmpty();
  }

  /**
   * Apply every registered enricher to a finished span.
   *
   * <p>Returns the span unchanged when no enricher is registered, or when every enricher returned
   * no tags. An enricher that throws or returns null is skipped and counted; it cannot fail the span
   * or the other enrichers.
   *
   * @param span the span about to be governed and exported, or null
   * @return the enriched span, or null when {@code span} was null
   */
  public static SpanContext apply(SpanContext span) {
    if (span == null || holders.isEmpty()) {
      return span;
    }
    SpanContext current = span;
    for (Holder holder : holders) {
      Map<String, String> tags = holder.enrich(current);
      if (tags == null || tags.isEmpty()) {
        continue;
      }
      SpanContext.Builder builder = current.toBuilder();
      for (Map.Entry<String, String> entry : tags.entrySet()) {
        builder.attribute(entry.getKey(), entry.getValue());
      }
      current = builder.build();
    }
    return current;
  }

  /** Remove every enricher. Tests use this so one test cannot see another's enricher. */
  public static void clear() {
    synchronized (TagEnrichers.class) {
      holders.clear();
    }
  }

  /** An enricher plus its priority and name, both captured at registration. */
  private static final class Holder {
    final TagEnricher enricher;
    final int priority;
    final String name;

    Holder(TagEnricher enricher) {
      this.enricher = enricher;
      this.priority = enricher.priority();
      String reported;
      try {
        reported = enricher.name();
      } catch (RuntimeException e) {
        reported = null;
      }
      this.name = reported == null || reported.trim().isEmpty() ? "unknown" : reported.trim();
    }

    Map<String, String> enrich(SpanContext span) {
      long start = System.nanoTime();
      try {
        Map<String, String> tags = enricher.enrichTags(span);
        ExtensionPerformanceTracker.record(
            ExtensionPerformanceTracker.KIND_TAG_ENRICHER, name, System.nanoTime() - start, false);
        return tags;
      } catch (RuntimeException e) {
        ExtensionPerformanceTracker.record(
            ExtensionPerformanceTracker.KIND_TAG_ENRICHER, name, System.nanoTime() - start, true);
        return null;
      }
    }
  }
}
