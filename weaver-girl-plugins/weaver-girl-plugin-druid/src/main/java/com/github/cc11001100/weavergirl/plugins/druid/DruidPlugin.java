package com.github.cc11001100.weavergirl.plugins.druid;

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
 * Alibaba Druid instrumentation plugin. Times SQL execution on Druid's statement wrappers and
 * connection acquisition on the data source, and reports statements or acquisitions that run longer
 * than the threshold.
 *
 * <p>{@code DruidPooledConnection} and {@code DruidDataSource} are concrete classes matched by name.
 * The statement wrappers extend {@code DruidStatementProxy} and the filter chain implements {@code
 * Filter}, so those are matched by superclass and interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowSqlThreshold} — Slow SQL threshold in ms (default: 200)
 *   <li>{@code slowConnectionThreshold} — Slow connection acquisition threshold in ms (default: 100)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class DruidPlugin extends AbstractPlugin {

  private static final Logger log = LoggerFactory.getLogger(DruidPlugin.class);

  private long slowSqlThresholdMs = 200;
  private long slowConnectionThresholdMs = 100;
  private boolean enabled = true;

  private static final String DATA_SOURCE = "com.alibaba.druid.pool.DruidDataSource";
  private static final String POOLED_CONNECTION = "com.alibaba.druid.pool.DruidPooledConnection";
  /** Base of every statement wrapper. Subclasses cover prepared and callable statements. */
  private static final String STATEMENT_PROXY =
      "com.alibaba.druid.proxy.jdbc.StatementProxyImpl";
  private static final String FILTER = "com.alibaba.druid.filter.Filter";

  private static final String STATEMENT_METHODS = "execute|executeQuery|executeUpdate|executeBatch";

  @Override
  public String name() {
    return "druid";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.alibaba.druid.pool.DruidDataSource";
  }

  @Override
  public void init(PluginContext context) {
    slowSqlThresholdMs = context.getConfigLong("slowSqlThreshold", 200);
    slowConnectionThresholdMs = context.getConfigLong("slowConnectionThreshold", 100);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    Interceptor sqlInterceptor = timingInterceptor(slowSqlThresholdMs, "slow-druid-sql", "druid-error");
    Interceptor connectionInterceptor =
        timingInterceptor(slowConnectionThresholdMs, "slow-druid-connection", "druid-error");

    registry.register(
        interceptSubclassOf(STATEMENT_PROXY)
            .methodPattern(STATEMENT_METHODS)
            .around(sqlInterceptor::before, sqlInterceptor::after)
            .onException(sqlInterceptor::onException)
            .priority(10)
            .build());

    registry.register(
        interceptImplementing(FILTER)
            .methodPattern(STATEMENT_METHODS)
            .around(sqlInterceptor::before, sqlInterceptor::after)
            .onException(sqlInterceptor::onException)
            .priority(20)
            .build());

    registry.register(
        intercept(POOLED_CONNECTION)
            .method("prepareStatement")
            .around(sqlInterceptor::before, sqlInterceptor::after)
            .onException(sqlInterceptor::onException)
            .priority(10)
            .build());

    registry.register(
        intercept(DATA_SOURCE)
            .method("getConnection")
            .around(connectionInterceptor::before, connectionInterceptor::after)
            .onException(connectionInterceptor::onException)
            .priority(10)
            .build());
  }

  private Interceptor timingInterceptor(long thresholdMs, String slowType, String errorType) {
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
        if (elapsedMs >= thresholdMs) {
          log.warn(
              "[SLOW-DRUID] {}.{} took {}ms",
              inv.getTargetClass().getSimpleName(),
              inv.getMethodName(),
              elapsedMs);
          InterceptorEventPublisher.getInstance()
              .publish(
                  InterceptorEvent.builder()
                      .type(slowType)
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
            "[DRUID-ERROR] {}.{} threw: {}",
            inv.getTargetClass().getSimpleName(),
            inv.getMethodName(),
            thrown != null ? thrown.getMessage() : "unknown");
        InterceptorEventPublisher.getInstance()
            .publish(
                InterceptorEvent.builder()
                    .type(errorType)
                    .plugin(name())
                    .className(inv.getTargetClass().getSimpleName())
                    .methodName(inv.getMethodName())
                    .attribute("error", thrown != null ? thrown.getMessage() : "")
                    .build());
      }
    };
  }
}
