package com.github.cc11001100.weavergirl.plugins.redisson;

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
 * Redisson instrumentation plugin. Times distributed lock acquisition and the command execution that
 * every Redisson object funnels through, and reports calls that run longer than the threshold.
 *
 * <p>{@code RLock} is an interface implemented by {@code RedissonLock} and its fair and read-write
 * variants, so it is matched by interface. {@code RedisExecutor} is the class every command runs on,
 * and later versions subclass it, so it is matched by superclass.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowLockThreshold} — Slow lock acquisition threshold in ms (default: 200)
 *   <li>{@code slowCommandThreshold} — Slow command threshold in ms (default: 100)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class RedissonPlugin extends AbstractPlugin {

  private static final Logger log = LoggerFactory.getLogger(RedissonPlugin.class);

  private long slowLockThresholdMs = 200;
  private long slowCommandThresholdMs = 100;
  private boolean enabled = true;

  private static final String LOCK = "org.redisson.api.RLock";
  private static final String EXECUTOR = "org.redisson.command.RedisExecutor";

  @Override
  public String name() {
    return "redisson";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.redisson.command.RedisExecutor";
  }

  @Override
  public void init(PluginContext context) {
    slowLockThresholdMs = context.getConfigLong("slowLockThreshold", 200);
    slowCommandThresholdMs = context.getConfigLong("slowCommandThreshold", 100);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    Interceptor lockInterceptor = timingInterceptor(slowLockThresholdMs, "slow-redisson-lock", "redisson-error");
    Interceptor commandInterceptor =
        timingInterceptor(slowCommandThresholdMs, "slow-redisson-command", "redisson-error");

    registry.register(
        interceptImplementing(LOCK)
            .methodPattern("lock|tryLock")
            .around(lockInterceptor::before, lockInterceptor::after)
            .onException(lockInterceptor::onException)
            .priority(10)
            .build());

    registry.register(
        interceptSubclassOf(EXECUTOR)
            .method("execute")
            .around(commandInterceptor::before, commandInterceptor::after)
            .onException(commandInterceptor::onException)
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
              "[SLOW-REDISSON] {}.{} took {}ms",
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
            "[REDISSON-ERROR] {}.{} threw: {}",
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
