package com.github.cc11001100.weavergirl.api.event;

/**
 * Governs one interceptor event before any listener or exporter sees it.
 *
 * <p>A transformer returns the event unchanged or modified, or {@code null} to drop it. This is
 * the hook for event hygiene between the intercepted code and the consumers: redacting a sensitive
 * attribute, rewriting a type or plugin name your backend expects, or dropping a noisy event class
 * before it reaches exporters and the event history.
 *
 * <p>Transformers run in {@link #priority()} order (ascending) in
 * {@link InterceptorEventPublisher#publish}, on the thread that published the event, so they must
 * be fast and non-blocking. A transformer that throws is skipped and counted; the event continues
 * unchanged, so one broken transformer cannot silence another.
 *
 * <p>Register through
 * {@link com.github.cc11001100.weavergirl.api.plugin.PluginContext#registerEventTransformer} so the
 * transformer is withdrawn when the plugin is unloaded; registering here directly is possible but
 * then the plugin must unregister it itself.
 *
 * @since 1.3.0
 */
public interface InterceptorEventTransformer {

  /**
   * A name for this transformer, used in stats and the performance tracker.
   *
   * @return the name, trimmed; null or blank is reported as {@code "unknown"}
   */
  String name();

  /**
   * Execution priority; lower values run first.
   *
   * @return the priority, default 0
   */
  default int priority() {
    return 0;
  }

  /**
   * Decide about one interceptor event.
   *
   * @param event the event as published, never null
   * @return the event to deliver (the same object, or a modified copy), or null to drop it
   */
  InterceptorEvent transform(InterceptorEvent event);
}