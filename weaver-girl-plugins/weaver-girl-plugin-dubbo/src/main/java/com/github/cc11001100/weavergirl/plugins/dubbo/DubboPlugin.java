package com.github.cc11001100.weavergirl.plugins.dubbo;

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
 * Apache Dubbo instrumentation plugin. Intercepts the RPC invocation chain on both the consumer and
 * the provider side to measure call latency and report slow or failed invocations.
 *
 * <p>Both target types are interfaces. Concrete implementations ({@code InvokerInvocationHandler},
 * {@code DubboInvoker}, {@code AbstractProxyInvoker}) are matched by the interface they implement, so
 * matching by exact class name would never fire.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowInvocationThreshold} — Slow invocation threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class DubboPlugin extends AbstractPlugin {

  private static final Logger log = LoggerFactory.getLogger(DubboPlugin.class);

  private long slowInvocationThresholdMs = 500;
  private boolean enabled = true;

  /** The consumer-side invocation entry. Every proxy call goes through it. */
  private static final String INVOKER = "org.apache.dubbo.rpc.Invoker";

  /** The provider-side entry that dispatches an incoming request onto the service implementation. */
  private static final String PROXY_INVOKER = "org.apache.dubbo.rpc.proxy.AbstractProxyInvoker";

  /** The filter chain both sides run. Filters are user-written, so match the interface. */
  private static final String FILTER = "org.apache.dubbo.rpc.Filter";

  @Override
  public String name() {
    return "dubbo";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.dubbo.rpc.Invoker";
  }

  @Override
  public void init(PluginContext context) {
    slowInvocationThresholdMs = context.getConfigLong("slowInvocationThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    Interceptor rpcInterceptor = timedInterceptor("slow-dubbo", "dubbo-error");

    registry.register(
        interceptImplementing(INVOKER)
            .method("invoke")
            .around(rpcInterceptor::before, rpcInterceptor::after)
            .onException(rpcInterceptor::onException)
            .priority(10)
            .build());

    registry.register(
        interceptSubclassOf(PROXY_INVOKER)
            .method("invoke")
            .around(rpcInterceptor::before, rpcInterceptor::after)
            .onException(rpcInterceptor::onException)
            .priority(10)
            .build());

    registry.register(
        interceptImplementing(FILTER)
            .method("invoke")
            .around(rpcInterceptor::before, rpcInterceptor::after)
            .onException(rpcInterceptor::onException)
            .priority(20)
            .build());
  }

  /**
   * An around interceptor that times one call and publishes an event when it is slow or fails.
   * Shared by every registration in this plugin, so one threshold and one event shape covers all of
   * them.
   */
  private Interceptor timedInterceptor(String slowType, String errorType) {
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
        if (elapsedMs >= slowInvocationThresholdMs) {
          log.warn(
              "[SLOW-DUBBO] {}.{} took {}ms",
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
            "[DUBBO-ERROR] {}.{} threw: {}",
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
