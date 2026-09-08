package com.github.cc11001100.weavergirl.plugins.grpc;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC instrumentation plugin. Intercepts gRPC server and client calls to: - Log method name,
 * timing - Detect slow calls
 *
 * <p>Target classes (string names, no compile dependency):
 *
 * <ul>
 *   <li>{@code io.grpc.stub.ServerCalls.UnaryMethod} &mdash; intercept invoke method (server)
 *   <li>{@code io.grpc.stub.ClientCalls} &mdash; intercept blockingUnaryCall, futureUnaryCall
 *       (client)
 * </ul>
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowThreshold} &mdash; Slow call threshold in ms (default: 2000)
 *   <li>{@code enabled} &mdash; Enable/disable (default: true)
 * </ul>
 */
public class GrpcPlugin extends AbstractPlugin {

  private static final Logger log = LoggerFactory.getLogger(GrpcPlugin.class);

  private long slowThresholdMs = 2000;
  private boolean enabled = true;

  // Target class names (as strings, no import dependency)
  private static final String SERVER_UNARY_METHOD = "io.grpc.stub.ServerCalls$UnaryMethod";
  private static final String CLIENT_CALLS = "io.grpc.stub.ClientCalls";

  @Override
  public String name() {
    return "grpc";
  }

  @Override
  public void init(PluginContext context) {
    slowThresholdMs = context.getConfigLong("slowThreshold", 2000);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(
      com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
    if (!enabled) return;

    Interceptor grpcInterceptor =
        new Interceptor() {
          private final ThreadLocal<Long> startTime = new ThreadLocal<>();

          @Override
          public void before(MethodInvocation inv) {
            startTime.set(System.nanoTime());
            String methodName = extractGrpcMethodName(inv);
            if (log.isDebugEnabled()) {
              log.debug(
                  "[GRPC] {} started: {}",
                  inv.getMethodName(),
                  methodName != null ? methodName : "unknown");
            }
          }

          @Override
          public void after(MethodInvocation inv) {
            Long start = startTime.get();
            startTime.remove();
            if (start != null) {
              long elapsedMs = (System.nanoTime() - start) / 1_000_000;
              String methodName = extractGrpcMethodName(inv);
              String methodInfo =
                  methodName != null
                      ? methodName
                      : inv.getTargetClass().getSimpleName() + "." + inv.getMethodName();

              if (elapsedMs >= slowThresholdMs) {
                log.warn(
                    "[SLOW-GRPC] {} took {}ms (threshold: {}ms)",
                    methodInfo,
                    elapsedMs,
                    slowThresholdMs);
                InterceptorEvent.Builder eventBuilder =
                    InterceptorEvent.builder()
                        .type("slow-grpc")
                        .plugin("grpc")
                        .className(inv.getTargetClass().getSimpleName())
                        .methodName(inv.getMethodName())
                        .durationMs(elapsedMs);
                if (methodName != null) {
                  eventBuilder.attribute("grpcMethod", methodName);
                }
                InterceptorEventPublisher.getInstance().publish(eventBuilder.build());
              } else if (log.isDebugEnabled()) {
                log.debug("[GRPC] {} took {}ms", methodInfo, elapsedMs);
              }
            }
          }

          @Override
          public void onException(MethodInvocation inv) {
            startTime.remove();
            log.warn(
                "[GRPC-ERROR] {}.{} threw: {}",
                inv.getTargetClass().getSimpleName(),
                inv.getMethodName(),
                inv.getThrowable() != null ? inv.getThrowable().getMessage() : "unknown");
            InterceptorEventPublisher.getInstance()
                .publish(
                    InterceptorEvent.builder()
                        .type("grpc-error")
                        .plugin("grpc")
                        .className(inv.getTargetClass().getSimpleName())
                        .methodName(inv.getMethodName())
                        .attribute(
                            "error",
                            inv.getThrowable() != null
                                ? inv.getThrowable().getMessage()
                                : "unknown")
                        .build());
          }
        };

    // Intercept gRPC server UnaryMethod.invoke
    // UnaryMethod is an abstract class — use bySuperClass to match
    // concrete implementations generated by gRPC at runtime
    registry.register(
        interceptSubclassOf(SERVER_UNARY_METHOD)
            .method("invoke")
            .around(inv -> grpcInterceptor.before(inv), inv -> grpcInterceptor.after(inv))
            .priority(10)
            .build());

    // Intercept gRPC client calls: blockingUnaryCall, futureUnaryCall
    registry.register(
        interceptClassPattern(CLIENT_CALLS.replace(".", "\\."))
            .methodPattern("blockingUnaryCall|futureUnaryCall|blockingServerStreamingCall")
            .around(inv -> grpcInterceptor.before(inv), inv -> grpcInterceptor.after(inv))
            .priority(10)
            .build());
  }

  /**
   * Try to extract the gRPC method name from the MethodInvocation via reflection. For server calls,
   * the method descriptor is typically accessible from the request or the method handler. For
   * client calls, the MethodDescriptor can be obtained from the first argument (MethodDescriptor or
   * Callable).
   */
  String extractGrpcMethodName(MethodInvocation inv) {
    try {
      Object[] args = inv.getArguments();
      if (args != null && args.length > 0) {
        Object arg = args[0];
        // Try getFullMethodName() (gRPC MethodDescriptor)
        try {
          java.lang.reflect.Method getFullMethodName =
              arg.getClass().getMethod("getFullMethodName");
          Object name = getFullMethodName.invoke(arg);
          if (name != null) return name.toString();
        } catch (NoSuchMethodException e) {
          // Try other approaches
        }
        // Try getType().getFullMethodName() on the request
        try {
          java.lang.reflect.Method getType = arg.getClass().getMethod("getType");
          Object type = getType.invoke(arg);
          if (type != null) {
            java.lang.reflect.Method getFullName = type.getClass().getMethod("getFullMethodName");
            Object name = getFullName.invoke(type);
            if (name != null) return name.toString();
          }
        } catch (NoSuchMethodException e) {
          // Fall through
        }
      }
    } catch (Exception e) {
      // Reflection failed — ignore
    }
    return null;
  }

  // Expose for testing
  long getSlowThresholdMs() {
    return slowThresholdMs;
  }

  boolean isEnabled() {
    return enabled;
  }
}
