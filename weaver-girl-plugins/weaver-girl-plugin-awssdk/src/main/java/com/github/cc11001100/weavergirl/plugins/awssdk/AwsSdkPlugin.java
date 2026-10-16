package com.github.cc11001100.weavergirl.plugins.awssdk;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * AWS SDK v1 instrumentation plugin. Times an API request being executed.
 *
 * <p>{@code AmazonHttpClient$RequestExecutor} is the class that runs one request and {@code
 * doExecute} is the method that performs it, so it is matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class AwsSdkPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 500;
  private boolean enabled = true;

  private static final String EXECUTOR = "com.amazonaws.http.AmazonHttpClient$RequestExecutor";

  @Override
  public String name() {
    return "awssdk";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.amazonaws.http.AmazonHttpClient";
  }

  @Override
  public void init(PluginContext context) {
    slowRequestThresholdMs = context.getConfigLong("slowRequestThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-awssdk", "awssdk-error", slowRequestThresholdMs);

    registry.register(
        intercept(EXECUTOR)
            .method("doExecute")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
