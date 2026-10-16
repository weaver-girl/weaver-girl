package com.github.cc11001100.weavergirl.plugins.feign;

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
 * OpenFeign instrumentation plugin. Times the HTTP call every Feign method ends up making, and
 * reports calls that run longer than the threshold.
 *
 * <p>{@code feign.Client} is the interface every transport implements ({@code Client.Default}, the
 * OkHttp client, the Apache HttpClient client), and {@code SynchronousMethodHandler} is the concrete
 * handler that decodes the response. Matching the interface covers every transport; matching the
 * handler covers the decode that follows.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowCallThreshold} — Slow call threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class FeignPlugin extends AbstractPlugin {

  private static final Logger log = LoggerFactory.getLogger(FeignPlugin.class);

  private long slowCallThresholdMs = 500;
  private boolean enabled = true;

  private static final String CLIENT = "feign.Client";
  private static final String METHOD_HANDLER = "feign.SynchronousMethodHandler";

  @Override
  public String name() {
    return "feign";
  }

  @Override
  public String instrumentedLibrary() {
    return "feign.Client";
  }

  @Override
  public void init(PluginContext context) {
    slowCallThresholdMs = context.getConfigLong("slowCallThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    Interceptor callInterceptor = timingInterceptor();

    registry.register(
        interceptImplementing(CLIENT)
            .method("execute")
            .around(callInterceptor::before, callInterceptor::after)
            .onException(callInterceptor::onException)
            .priority(10)
            .build());

    registry.register(
        intercept(METHOD_HANDLER)
            .method("executeAndDecode")
            .around(callInterceptor::before, callInterceptor::after)
            .onException(callInterceptor::onException)
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
        if (elapsedMs >= slowCallThresholdMs) {
          log.warn(
              "[SLOW-FEIGN] {}.{} took {}ms",
              inv.getTargetClass().getSimpleName(),
              inv.getMethodName(),
              elapsedMs);
          InterceptorEventPublisher.getInstance()
              .publish(
                  InterceptorEvent.builder()
                      .type("slow-feign")
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
            "[FEIGN-ERROR] {}.{} threw: {}",
            inv.getTargetClass().getSimpleName(),
            inv.getMethodName(),
            thrown != null ? thrown.getMessage() : "unknown");
        InterceptorEventPublisher.getInstance()
            .publish(
                InterceptorEvent.builder()
                    .type("feign-error")
                    .plugin(name())
                    .className(inv.getTargetClass().getSimpleName())
                    .methodName(inv.getMethodName())
                    .attribute("error", thrown != null ? thrown.getMessage() : "")
                    .build());
      }
    };
  }
}
