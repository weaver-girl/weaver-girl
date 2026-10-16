package com.github.cc11001100.weavergirl.plugins.mybatis;

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
 * MyBatis instrumentation plugin. Intercepts statement execution so every mapped query is timed,
 * slow queries are reported, and the mapped statement id is recorded with the event.
 *
 * <p>The executor and the statement handler are interfaces; the classes that actually run ({@code
 * CachingExecutor}, {@code SimpleExecutor}, {@code PreparedStatementHandler}) implement them, so the
 * match is by interface rather than by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowQueryThreshold} — Slow query threshold in ms (default: 200)
 *   <li>{@code logStatementId} — Whether to record the mapped statement id (default: true)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class MyBatisPlugin extends AbstractPlugin {

  private static final Logger log = LoggerFactory.getLogger(MyBatisPlugin.class);

  private long slowQueryThresholdMs = 200;
  private boolean logStatementId = true;
  private boolean enabled = true;

  private static final String EXECUTOR = "org.apache.ibatis.executor.Executor";
  private static final String STATEMENT_HANDLER = "org.apache.ibatis.executor.statement.StatementHandler";

  /** Executor methods that actually run a mapped statement. */
  private static final String EXECUTOR_METHODS = "query|update";

  /** StatementHandler methods that run the prepared statement. */
  private static final String STATEMENT_METHODS = "query|update|batch";

  @Override
  public String name() {
    return "mybatis";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.ibatis.executor.Executor";
  }

  @Override
  public void init(PluginContext context) {
    slowQueryThresholdMs = context.getConfigLong("slowQueryThreshold", 200);
    logStatementId = context.getConfigBoolean("logStatementId", true);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    Interceptor queryInterceptor = queryTimingInterceptor();

    registry.register(
        interceptImplementing(EXECUTOR)
            .methodPattern(EXECUTOR_METHODS)
            .around(queryInterceptor::before, queryInterceptor::after)
            .onException(queryInterceptor::onException)
            .priority(10)
            .build());

    registry.register(
        interceptImplementing(STATEMENT_HANDLER)
            .methodPattern(STATEMENT_METHODS)
            .around(queryInterceptor::before, queryInterceptor::after)
            .onException(queryInterceptor::onException)
            .priority(10)
            .build());
  }

  private Interceptor queryTimingInterceptor() {
    return new Interceptor() {
      private final ThreadLocal<Long> startTime = new ThreadLocal<>();

      @Override
      public void before(MethodInvocation inv) {
        startTime.set(System.nanoTime());
        if (log.isDebugEnabled()) {
          log.debug(
              "[MYBATIS] {}.{}{}",
              inv.getTargetClass().getSimpleName(),
              inv.getMethodName(),
              statementInfo(inv));
        }
      }

      @Override
      public void after(MethodInvocation inv) {
        Long start = startTime.get();
        startTime.remove();
        if (start == null) return;
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (elapsedMs >= slowQueryThresholdMs) {
          String statementId = statementId(inv);
          log.warn(
              "[SLOW-MYBATIS] {}.{} took {}ms{}",
              inv.getTargetClass().getSimpleName(),
              inv.getMethodName(),
              elapsedMs,
              statementInfo(inv));
          InterceptorEvent.Builder event =
              InterceptorEvent.builder()
                  .type("slow-mybatis")
                  .plugin(name())
                  .className(inv.getTargetClass().getSimpleName())
                  .methodName(inv.getMethodName())
                  .durationMs(elapsedMs);
          if (statementId != null) {
            event.attribute("statementId", statementId);
          }
          InterceptorEventPublisher.getInstance().publish(event.build());
        }
      }

      @Override
      public void onException(MethodInvocation inv) {
        startTime.remove();
        Throwable thrown = inv.getThrowable();
        log.warn(
            "[MYBATIS-ERROR] {}.{} threw: {}",
            inv.getTargetClass().getSimpleName(),
            inv.getMethodName(),
            thrown != null ? thrown.getMessage() : "unknown");
        InterceptorEventPublisher.getInstance()
            .publish(
                InterceptorEvent.builder()
                    .type("mybatis-error")
                    .plugin(name())
                    .className(inv.getTargetClass().getSimpleName())
                    .methodName(inv.getMethodName())
                    .attribute("error", thrown != null ? thrown.getMessage() : "")
                    .build());
      }
    };
  }

  /**
   * The mapped statement id. {@code Executor.query} and {@code Executor.update} take a {@code
   * MappedStatement} as their first argument, and its id is the mapper method. Read through {@code
   * getId()} so this plugin never depends on the MyBatis classes; entry points that pass a JDBC
   * statement instead have no id to read.
   */
  private String statementId(MethodInvocation inv) {
    if (!logStatementId) return null;
    Object[] args = inv.getArguments();
    if (args == null || args.length == 0 || args[0] == null) return null;
    Object first = args[0];
    if (first instanceof String) return (String) first;
    try {
      java.lang.reflect.Method getId = first.getClass().getMethod("getId");
      if (getId.getReturnType() != String.class) return null;
      Object id = getId.invoke(first);
      return id instanceof String ? (String) id : null;
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private String statementInfo(MethodInvocation inv) {
    String id = statementId(inv);
    return id != null ? " statement=" + id : "";
  }
}
