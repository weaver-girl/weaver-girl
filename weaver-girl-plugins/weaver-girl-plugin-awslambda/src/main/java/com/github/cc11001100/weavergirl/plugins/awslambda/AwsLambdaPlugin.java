package com.github.cc11001100.weavergirl.plugins.awslambda;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * AWS Lambda instrumentation plugin. Times one function invocation.
 *
 * <p>com.amazonaws.services.lambda.runtime.RequestHandler is the interface every handler implements and handleRequest is the method the runtime calls with the event and the context, so it is matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowInvocationThreshold} — Slow invocation threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class AwsLambdaPlugin extends AbstractPlugin {

  private long thresholdMs = 1000;
  private boolean enabled = true;

  private static final String TARGET = "com.amazonaws.services.lambda.runtime.RequestHandler";

  @Override
  public String name() {
    return "awslambda";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.amazonaws.services.lambda.runtime.RequestHandler";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowInvocationThreshold", 1000);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-awslambda", "awslambda-error", thresholdMs);

    registry.register(
        interceptImplementing(TARGET)
            .method("handleRequest")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
