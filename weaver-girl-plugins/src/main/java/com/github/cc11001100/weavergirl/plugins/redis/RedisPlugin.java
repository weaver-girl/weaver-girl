package com.github.cc11001100.weavergirl.plugins.redis;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis client instrumentation plugin. Intercepts Jedis and Lettuce Redis clients to: - Measure
 * command execution time - Detect slow commands - Log command names and keys
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowCommandThreshold} — Slow command threshold in ms (default: 100)
 *   <li>{@code logKeys} — Whether to log Redis keys (default: true)
 *   <li>{@code maxKeyLength} — Max key length to log (default: 50)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class RedisPlugin extends AbstractPlugin {

  private static final Logger log = LoggerFactory.getLogger(RedisPlugin.class);

  private long slowCommandThresholdMs = 100;
  private boolean logKeys = true;
  private int maxKeyLength = 50;
  private boolean enabled = true;

  // Target class names (as strings, no import dependency)
  private static final String JEDIS = "redis.clients.jedis.Jedis";
  private static final String JEDIS_POOL = "redis.clients.jedis.JedisPool";
  private static final String LETTUCE_STATEFUL = "io.lettuce.core.RedisClient";
  private static final String LETTUCE_COMMANDS = "io.lettuce.core.api.StatefulRedisConnection";

  @Override
  public String name() {
    return "redis";
  }

  @Override
  public void init(PluginContext context) {
    slowCommandThresholdMs = context.getConfigLong("slowCommandThreshold", 100);
    logKeys = context.getConfigBoolean("logKeys", true);
    maxKeyLength = context.getConfigInt("maxKeyLength", 50);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(
      com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
    if (!enabled) return;

    Interceptor redisInterceptor =
        new Interceptor() {
          private final ThreadLocal<Long> startTime = new ThreadLocal<>();

          @Override
          public void before(MethodInvocation inv) {
            startTime.set(System.nanoTime());
            if (log.isDebugEnabled()) {
              String key = extractKey(inv);
              String keyInfo =
                  (logKeys && key != null) ? " key=" + truncate(key, maxKeyLength) : "";
              log.debug(
                  "[REDIS] {}.{}{}",
                  inv.getTargetClass().getSimpleName(),
                  inv.getMethodName(),
                  keyInfo);
            }
          }

          @Override
          public void after(MethodInvocation inv) {
            Long start = startTime.get();
            startTime.remove();
            if (start != null) {
              long elapsedMs = (System.nanoTime() - start) / 1_000_000;
              if (elapsedMs >= slowCommandThresholdMs) {
                String key = extractKey(inv);
                String keyInfo =
                    (logKeys && key != null) ? " key=" + truncate(key, maxKeyLength) : "";
                log.warn(
                    "[SLOW-REDIS] {}.{} took {}ms{}",
                    inv.getTargetClass().getSimpleName(),
                    inv.getMethodName(),
                    elapsedMs,
                    keyInfo);
                InterceptorEvent.Builder eventBuilder =
                    InterceptorEvent.builder()
                        .type("slow-redis")
                        .plugin("redis")
                        .className(inv.getTargetClass().getSimpleName())
                        .methodName(inv.getMethodName())
                        .durationMs(elapsedMs);
                if (key != null) {
                  eventBuilder.attribute("key", truncate(key, maxKeyLength));
                }
                InterceptorEventPublisher.getInstance().publish(eventBuilder.build());
              } else if (log.isDebugEnabled()) {
                log.debug(
                    "[REDIS] {}.{} took {}ms",
                    inv.getTargetClass().getSimpleName(),
                    inv.getMethodName(),
                    elapsedMs);
              }
            }
          }

          @Override
          public void onException(MethodInvocation inv) {
            startTime.remove();
            log.warn(
                "[REDIS-ERROR] {}.{} threw: {}",
                inv.getTargetClass().getSimpleName(),
                inv.getMethodName(),
                inv.getThrowable().getMessage());
            InterceptorEventPublisher.getInstance()
                .publish(
                    InterceptorEvent.builder()
                        .type("redis-error")
                        .plugin("redis")
                        .className(inv.getTargetClass().getSimpleName())
                        .methodName(inv.getMethodName())
                        .attribute("error", inv.getThrowable().getMessage())
                        .build());
          }
        };

    // Intercept Jedis commands
    registry.register(
        intercept(JEDIS)
            .methodPattern(
                "get|set|del|hget|hset|lpush|rpush|sadd|zadd|expire|exists|incr|decr|publish|subscribe|ping|info|flushDB|flushAll")
            .around(inv -> redisInterceptor.before(inv), inv -> redisInterceptor.after(inv))
            .priority(10)
            .build());

    // Intercept Lettuce commands
    // StatefulRedisConnection is an interface — use byInterface to match
    // concrete implementations like StatefulRedisConnectionImpl
    registry.register(
        interceptImplementing(LETTUCE_COMMANDS)
            .methodPattern(
                "get|set|del|hget|hset|lpush|rpush|sadd|zadd|expire|exists|incr|decr|publish|subscribe|ping|info")
            .around(inv -> redisInterceptor.before(inv), inv -> redisInterceptor.after(inv))
            .priority(10)
            .build());
  }

  /** Try to extract the Redis key from the first argument. */
  private String extractKey(MethodInvocation inv) {
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

  private String truncate(String s, int maxLen) {
    return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
  }
}
