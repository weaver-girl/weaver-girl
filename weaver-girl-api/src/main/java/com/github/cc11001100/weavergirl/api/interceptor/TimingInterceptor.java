package com.github.cc11001100.weavergirl.api.interceptor;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import java.util.function.BiConsumer;

/**
 * An interceptor that times one call and publishes an event when it runs longer than a threshold or
 * throws.
 *
 * <p>Most instrumentation plugins do exactly this and nothing else, so the timing, the threshold
 * check and the event shape live here once instead of being copied into every plugin. A plugin keeps
 * its own logging; this class only measures and reports, which is why it has no logger of its own.
 * Plugins that wanted that logging inline (for example a {@code [SLOW-CAFFEINE]} warning with a
 * truncated key) pass it in as a callback through {@link Builder}: it runs at the same moment, and
 * the event is still published exactly once.
 *
 * <p>The start time is held in a {@link ThreadLocal}, so a call that begins on one thread and ends on
 * another records nothing rather than a wrong duration.
 *
 * @since 1.0.0
 */
public class TimingInterceptor implements Interceptor {

  private final String plugin;
  private final String slowType;
  private final String errorType;
  private final long thresholdMs;
  private final BiConsumer<MethodInvocation, Long> slowLog;
  private final BiConsumer<MethodInvocation, Throwable> errorLog;
  private final ThreadLocal<Long> startTime = new ThreadLocal<>();

  /**
   * @param plugin the plugin name recorded on every event
   * @param slowType the event type published when a call exceeds the threshold
   * @param errorType the event type published when a call throws
   * @param thresholdMs the duration, in milliseconds, at or above which a call is reported as slow
   */
  public TimingInterceptor(String plugin, String slowType, String errorType, long thresholdMs) {
    this(plugin, slowType, errorType, thresholdMs, null, null);
  }

  private TimingInterceptor(
      String plugin,
      String slowType,
      String errorType,
      long thresholdMs,
      BiConsumer<MethodInvocation, Long> slowLog,
      BiConsumer<MethodInvocation, Throwable> errorLog) {
    this.plugin = plugin;
    this.slowType = slowType;
    this.errorType = errorType;
    this.thresholdMs = thresholdMs;
    this.slowLog = slowLog;
    this.errorLog = errorLog;
  }

  /** A builder for the cases that need plugin-specific logging alongside the timing. */
  public static Builder builder(String plugin, String slowType, String errorType, long thresholdMs) {
    return new Builder(plugin, slowType, errorType, thresholdMs);
  }

  /** The threshold this interceptor reports against, in milliseconds. */
  public long getThresholdMs() {
    return thresholdMs;
  }

  @Override
  public void before(MethodInvocation invocation) {
    startTime.set(System.nanoTime());
  }

  @Override
  public void after(MethodInvocation invocation) {
    Long start = startTime.get();
    startTime.remove();
    if (start == null) return;
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    if (elapsedMs < thresholdMs) return;
    if (slowLog != null) {
      slowLog.accept(invocation, elapsedMs);
    }
    InterceptorEventPublisher.getInstance()
        .publish(
            InterceptorEvent.builder()
                .type(slowType)
                .plugin(plugin)
                .className(invocation.getTargetClass().getSimpleName())
                .methodName(invocation.getMethodName())
                .durationMs(elapsedMs)
                .build());
  }

  @Override
  public void onException(MethodInvocation invocation) {
    startTime.remove();
    Throwable thrown = invocation.getThrowable();
    if (errorLog != null) {
      errorLog.accept(invocation, thrown);
    }
    InterceptorEventPublisher.getInstance()
        .publish(
            InterceptorEvent.builder()
                .type(errorType)
                .plugin(plugin)
                .className(invocation.getTargetClass().getSimpleName())
                .methodName(invocation.getMethodName())
                .attribute("error", thrown != null ? thrown.getMessage() : "")
                .build());
  }

  /** Collects the plugin-specific logging hooks for a {@link TimingInterceptor}. */
  public static final class Builder {
    private final String plugin;
    private final String slowType;
    private final String errorType;
    private final long thresholdMs;
    private BiConsumer<MethodInvocation, Long> slowLog;
    private BiConsumer<MethodInvocation, Throwable> errorLog;

    private Builder(String plugin, String slowType, String errorType, long thresholdMs) {
      this.plugin = plugin;
      this.slowType = slowType;
      this.errorType = errorType;
      this.thresholdMs = thresholdMs;
    }

    /**
     * Runs at the moment a call is found slow, before the event is published.
     *
     * @param slowLog receives the invocation and the measured duration in milliseconds
     */
    public Builder slowLog(BiConsumer<MethodInvocation, Long> slowLog) {
      this.slowLog = slowLog;
      return this;
    }

    /**
     * Runs at the moment a call throws, before the event is published.
     *
     * @param errorLog receives the invocation and the throwable (possibly {@code null})
     */
    public Builder errorLog(BiConsumer<MethodInvocation, Throwable> errorLog) {
      this.errorLog = errorLog;
      return this;
    }

    public TimingInterceptor build() {
      return new TimingInterceptor(plugin, slowType, errorType, thresholdMs, slowLog, errorLog);
    }
  }
}
