package com.github.cc11001100.weavergirl.plugins.caffeine;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
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
    return TimingInterceptor.builder(name(), "slow-caffeine", "caffeine-error", slowLoadThresholdMs)
        .slowLog(
            (inv, elapsedMs) ->
                log.warn(
                    "[SLOW-CAFFEINE] {}.{} took {}ms",
                    inv.getTargetClass().getSimpleName(),
                    inv.getMethodName(),
                    elapsedMs))
        .errorLog(
            (inv, thrown) ->
                log.warn(
                    "[CAFFEINE-ERROR] {}.{} threw: {}",
                    inv.getTargetClass().getSimpleName(),
                    inv.getMethodName(),
                    thrown != null ? thrown.getMessage() : "unknown"))
        .build();
  }
}
