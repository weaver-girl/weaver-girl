package com.github.cc11001100.weavergirl.plugins.caffeine;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caffeine cache instrumentation plugin. Times the loads that miss the cache, which is where a cache
 * hides a slow computation, and reports loads that run longer than the threshold.
 *
 * <p>{@code BoundedLocalCache} is the implementation behind every size- or time-bounded cache, and
 * {@code computeIfAbsent} is the method both {@code Cache.get(key, mappingFunction)} and {@code
 * LoadingCache.get(key)} delegate to. Unbounded caches extend {@code UnboundedLocalCache}, so both
 * are covered by superclass matching.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowLoadThreshold} — Slow load threshold in ms (default: 100)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class CaffeinePlugin extends AbstractPlugin {

  private static final Logger log = LoggerFactory.getLogger(CaffeinePlugin.class);

  private long slowLoadThresholdMs = 100;
  private boolean enabled = true;

  private static final String BOUNDED = "com.github.benmanes.caffeine.cache.BoundedLocalCache";
  private static final String UNBOUNDED = "com.github.benmanes.caffeine.cache.UnboundedLocalCache";

  private static final String LOAD_METHODS = "computeIfAbsent|get";

  @Override
  public String name() {
    return "caffeine";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.github.benmanes.caffeine.cache.BoundedLocalCache";
  }

  @Override
  public void init(PluginContext context) {
    slowLoadThresholdMs = context.getConfigLong("slowLoadThreshold", 100);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    Interceptor loadInterceptor = timingInterceptor();

    registry.register(
        interceptSubclassOf(BOUNDED)
            .methodPattern(LOAD_METHODS)
            .around(loadInterceptor::before, loadInterceptor::after)
            .onException(loadInterceptor::onException)
            .priority(10)
            .build());

    registry.register(
        interceptSubclassOf(UNBOUNDED)
            .methodPattern(LOAD_METHODS)
            .around(loadInterceptor::before, loadInterceptor::after)
            .onException(loadInterceptor::onException)
            .priority(10)
            .build());
  }

  private Interceptor timingInterceptor() {
    return new Interceptor() {
      private final ThreadLocal<Long> startTime = new ThreadLocal<>();

      @Override
      public void before(MethodInvocation inv) {
        startTime.set(System.nanoTime());
      }

      @Override
      public void after(MethodInvocation inv) {
        Long start = startTime.get();
        startTime.remove();
        if (start == null) return;
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (elapsedMs >= slowLoadThresholdMs) {
          log.warn(
              "[SLOW-CAFFEINE] {}.{} took {}ms",
              inv.getTargetClass().getSimpleName(),
              inv.getMethodName(),
              elapsedMs);
          InterceptorEventPublisher.getInstance()
              .publish(
                  InterceptorEvent.builder()
                      .type("slow-caffeine")
                      .plugin(name())
                      .className(inv.getTargetClass().getSimpleName())
                      .methodName(inv.getMethodName())
                      .durationMs(elapsedMs)
                      .build());
        }
      }

      @Override
      public void onException(MethodInvocation inv) {
        startTime.remove();
        Throwable thrown = inv.getThrowable();
        log.warn(
            "[CAFFEINE-ERROR] {}.{} threw: {}",
            inv.getTargetClass().getSimpleName(),
            inv.getMethodName(),
            thrown != null ? thrown.getMessage() : "unknown");
        InterceptorEventPublisher.getInstance()
            .publish(
                InterceptorEvent.builder()
                    .type("caffeine-error")
                    .plugin(name())
                    .className(inv.getTargetClass().getSimpleName())
                    .methodName(inv.getMethodName())
                    .attribute("error", thrown != null ? thrown.getMessage() : "")
                    .build());
      }
    };
  }
}
