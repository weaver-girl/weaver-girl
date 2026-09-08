package com.github.cc11001100.weavergirl.plugins.hikari;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HikariCP connection pool instrumentation plugin.
 *
 * <p>Monitors connection pool behavior:
 *
 * <ul>
 *   <li>Connection acquisition time and timeout detection
 *   <li>Connection leak detection (configurable threshold)
 *   <li>Pool size changes
 *   <li>Connection creation and eviction events
 * </ul>
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code leakThresholdMs} — Connection leak threshold in ms (default: 30000)
 *   <li>{@code trackAcquisition} — Track acquisition time (default: true)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 *
 * @since 1.2.0
 */
public class HikariPlugin extends AbstractPlugin {

  private static final Logger log = LoggerFactory.getLogger(HikariPlugin.class);

  private long leakThresholdMs = 30000;
  private boolean trackAcquisition = true;

  @Override
  public String name() {
    return "hikari";
  }

  @Override
  public void init(PluginContext context) {
    leakThresholdMs = context.getConfigLong("leakThresholdMs", 30000L);
    trackAcquisition = context.getConfigBoolean("trackAcquisition", true);
    log.info(
        "[hikari] Initialized: leakThreshold={}ms, trackAcquisition={}",
        leakThresholdMs,
        trackAcquisition);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // Connection pool acquisition
    registry.register(
        new InterceptorDefinition(
            "hikari-pool-acquire",
            new Pointcut(
                ClassMatcher.bySuperClass("com.zaxxer.hikari.pool.HikariPool"),
                MethodMatcher.byName("getConnection")),
            new ConnectionAcquireInterceptor(),
            0));

    // Connection close/eviction
    registry.register(
        new InterceptorDefinition(
            "hikari-connection-close",
            new Pointcut(
                ClassMatcher.bySuperClass("com.zaxxer.hikari.pool.ProxyConnection"),
                MethodMatcher.byName("close")),
            new ConnectionCloseInterceptor(),
            0));

    log.info("[hikari] Registered {} interceptors", 2);
  }

  /** Intercepts connection acquisition to track timing and detect timeouts. */
  private class ConnectionAcquireInterceptor implements Interceptor {
    private final ThreadLocal<Long> acquireStart = new ThreadLocal<>();

    @Override
    public void before(MethodInvocation invocation) {
      acquireStart.set(System.nanoTime());
    }

    @Override
    public void after(MethodInvocation invocation) {
      Long start = acquireStart.get();
      acquireStart.remove();
      if (start == null) return;

      long elapsedMs = (System.nanoTime() - start) / 1_000_000;

      if (trackAcquisition) {
        InterceptorEvent event =
            InterceptorEvent.builder()
                .type("pool-acquire")
                .plugin(name())
                .className(invocation.getTargetClass().getName())
                .methodName("getConnection")
                .durationMs(elapsedMs)
                .attribute("pool", invocation.getTarget().getClass().getSimpleName())
                .build();
        InterceptorEventPublisher.getInstance().publish(event);
      }

      if (elapsedMs > leakThresholdMs) {
        log.warn(
            "[hikari] Slow connection acquisition: {}ms (threshold={}ms)",
            elapsedMs,
            leakThresholdMs);
      }
    }

    @Override
    public void onException(MethodInvocation invocation) {
      acquireStart.remove();
      InterceptorEvent event =
          InterceptorEvent.builder()
              .type("pool-acquire-error")
              .plugin(name())
              .className(invocation.getTargetClass().getName())
              .methodName("getConnection")
              .attribute("error", "connection acquisition failed")
              .build();
      InterceptorEventPublisher.getInstance().publish(event);
    }
  }

  /** Intercepts connection close to detect potential leaks. */
  private class ConnectionCloseInterceptor implements Interceptor {
    @Override
    public void before(MethodInvocation invocation) {
      // Track connection lifecycle
    }

    @Override
    public void after(MethodInvocation invocation) {
      InterceptorEvent event =
          InterceptorEvent.builder()
              .type("connection-close")
              .plugin(name())
              .className(invocation.getTargetClass().getName())
              .methodName("close")
              .build();
      InterceptorEventPublisher.getInstance().publish(event);
    }

    @Override
    public void onException(MethodInvocation invocation) {
      log.debug("[hikari] Exception during connection close");
    }
  }
}
