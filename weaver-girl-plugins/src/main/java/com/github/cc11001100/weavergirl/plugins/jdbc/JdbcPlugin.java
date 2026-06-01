package com.github.cc11001100.weavergirl.plugins.jdbc;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC instrumentation plugin.
 * Intercepts Statement and PreparedStatement methods to:
 * - Log SQL text before execution
 * - Measure SQL execution time
 * - Detect slow queries (configurable threshold)
 * - Track connection lifecycle
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code slowQueryThreshold} — Slow query threshold in ms (default: 1000)</li>
 *   <li>{@code logSql} — Whether to log SQL text (default: true)</li>
 *   <li>{@code maxSqlLength} — Max SQL length to log (default: 200)</li>
 *   <li>{@code enabled} — Enable/disable (default: true)</li>
 * </ul>
 */
public class JdbcPlugin extends AbstractPlugin {

    private static final Logger log = LoggerFactory.getLogger(JdbcPlugin.class);

    private long slowQueryThresholdMs = 1000;
    private boolean logSql = true;
    private int maxSqlLength = 200;
    private boolean enabled = true;

    // Target class names
    private static final String STATEMENT = "java.sql.Statement";
    private static final String PREPARED_STATEMENT = "java.sql.PreparedStatement";
    private static final String CONNECTION = "java.sql.Connection";
    private static final String DATASOURCE = "javax.sql.DataSource";
    // Common implementations
    private static final String HIKARI_DATASOURCE = "com.zaxxer.hikari.HikariDataSource";

    @Override
    public String name() {
        return "jdbc";
    }

    @Override
    public void init(PluginContext context) {
        String thresholdStr = context.getConfig("slowQueryThreshold", "1000");
        try { slowQueryThresholdMs = Long.parseLong(thresholdStr); } catch (NumberFormatException e) { slowQueryThresholdMs = 1000; }
        logSql = "true".equalsIgnoreCase(context.getConfig("logSql", "true"));
        String maxLenStr = context.getConfig("maxSqlLength", "200");
        try { maxSqlLength = Integer.parseInt(maxLenStr); } catch (NumberFormatException e) { maxSqlLength = 200; }
        enabled = "true".equalsIgnoreCase(context.getConfig("enabled", "true"));
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
        if (!enabled) return;

        Interceptor executeInterceptor = new Interceptor() {
            private final ThreadLocal<Long> startTime = new ThreadLocal<>();

            @Override
            public void before(MethodInvocation inv) {
                startTime.set(System.nanoTime());
                if (logSql && log.isDebugEnabled()) {
                    // Try to extract SQL from first argument (PreparedStatement) or target
                    String sql = extractSql(inv);
                    if (sql != null) {
                        String truncated = sql.length() > maxSqlLength ? sql.substring(0, maxSqlLength) + "..." : sql;
                        log.debug("[JDBC] Executing SQL: {}", truncated);
                    }
                }
            }

            @Override
            public void after(MethodInvocation inv) {
                long elapsedMs = (System.nanoTime() - startTime.get()) / 1_000_000;
                startTime.remove();
                String sql = extractSql(inv);
                if (elapsedMs >= slowQueryThresholdMs) {
                    String sqlInfo = sql != null ? " SQL: " + (sql.length() > maxSqlLength ? sql.substring(0, maxSqlLength) + "..." : sql) : "";
                    log.warn("[SLOW-QUERY] {}.{} took {}ms{}", inv.getTargetClass().getSimpleName(), inv.getMethodName(), elapsedMs, sqlInfo);
                    // Publish structured event for metrics/trace exporters
                    InterceptorEvent.Builder eventBuilder = InterceptorEvent.builder()
                            .type("slow-query")
                            .plugin("jdbc")
                            .className(inv.getTargetClass().getSimpleName())
                            .methodName(inv.getMethodName())
                            .durationMs(elapsedMs);
                    if (sql != null) {
                        eventBuilder.attribute("sql", sql.length() > maxSqlLength ? sql.substring(0, maxSqlLength) + "..." : sql);
                    }
                    InterceptorEventPublisher.getInstance().publish(eventBuilder.build());
                } else if (log.isDebugEnabled()) {
                    log.debug("[JDBC] {}.{} took {}ms", inv.getTargetClass().getSimpleName(), inv.getMethodName(), elapsedMs);
                }
            }

            @Override
            public void onException(MethodInvocation inv) {
                startTime.remove();
                log.warn("[JDBC-ERROR] {}.{} threw: {}", inv.getTargetClass().getSimpleName(), inv.getMethodName(), inv.getThrowable().getMessage());
                InterceptorEventPublisher.getInstance().publish(
                        InterceptorEvent.builder()
                                .type("jdbc-error")
                                .plugin("jdbc")
                                .className(inv.getTargetClass().getSimpleName())
                                .methodName(inv.getMethodName())
                                .attribute("error", inv.getThrowable().getMessage())
                                .build()
                );
            }
        };

        // Intercept Statement.execute methods
        // Use byInterface because java.sql.Statement is an interface — real drivers use
        // implementations like PgStatement, MySQLStatement, HikariProxyStatement, etc.
        registry.register(new InterceptorDefinition(
            name() + "-" + STATEMENT + "-execute",
            new Pointcut(ClassMatcher.byInterface(STATEMENT), MethodMatcher.byNamePattern("execute|executeQuery|executeUpdate|executeBatch")),
            executeInterceptor, 10
        ));

        // Intercept PreparedStatement.execute methods
        // PreparedStatement extends Statement — use byInterface for same reason
        registry.register(new InterceptorDefinition(
            name() + "-" + PREPARED_STATEMENT + "-execute",
            new Pointcut(ClassMatcher.byInterface(PREPARED_STATEMENT), MethodMatcher.byNamePattern("execute|executeQuery|executeUpdate|executeBatch")),
            executeInterceptor, 10
        ));

        // Intercept Connection methods
        Interceptor connectionInterceptor = new Interceptor() {
            @Override
            public void after(MethodInvocation inv) {
                if (log.isDebugEnabled()) {
                    log.debug("[JDBC-CONN] {}.{} completed", inv.getTargetClass().getSimpleName(), inv.getMethodName());
                }
            }

            @Override
            public void onException(MethodInvocation inv) {
                log.warn("[JDBC-CONN-ERROR] {}.{} threw: {}", inv.getTargetClass().getSimpleName(), inv.getMethodName(), inv.getThrowable().getMessage());
            }
        };

        registry.register(new InterceptorDefinition(
            name() + "-" + CONNECTION + "-prepare",
            new Pointcut(ClassMatcher.byInterface(CONNECTION), MethodMatcher.byNamePattern("prepareStatement|createStatement|prepareCall")),
            connectionInterceptor, 10
        ));
    }

    /**
     * Try to extract SQL text from the MethodInvocation.
     * For PreparedStatement, SQL is typically in the constructor.
     * For Statement.execute(), SQL is typically the first argument.
     */
    private String extractSql(MethodInvocation inv) {
        try {
            Object[] args = inv.getArguments();
            if (args != null && args.length > 0 && args[0] instanceof String) {
                return (String) args[0];
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    // Expose for testing
    String extractSqlForTest(MethodInvocation inv) {
        return extractSql(inv);
    }

    long getSlowQueryThresholdMs() {
        return slowQueryThresholdMs;
    }

    boolean isLogSql() {
        return logSql;
    }

    int getMaxSqlLength() {
        return maxSqlLength;
    }

    boolean isEnabled() {
        return enabled;
    }
}
