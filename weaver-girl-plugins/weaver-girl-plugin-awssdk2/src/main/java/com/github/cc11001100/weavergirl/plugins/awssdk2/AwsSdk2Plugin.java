package com.github.cc11001100.weavergirl.plugins.awssdk2;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * AWS SDK v2 instrumentation plugin. Times an asynchronous API request being executed.
 *
 * <p>{@code AwsAsyncClientHandler} is the class that runs one request and {@code execute} is the
 * method that performs it, so it is matched by exact name. The synchronous handler is covered by
 * the underlying HTTP client instrumentation.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class AwsSdk2Plugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 500;
  private boolean enabled = true;

  private static final String HANDLER =
      "software.amazon.awssdk.awscore.client.handler.AwsAsyncClientHandler";

  @Override
  public String name() {
    return "awssdk2";
  }

  @Override
  public String instrumentedLibrary() {
    return "software.amazon.awssdk.awscore.client.handler.AwsAsyncClientHandler";
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
        new TimingInterceptor(name(), "slow-awssdk2", "awssdk2-error", slowRequestThresholdMs);

    registry.register(
        intercept(HANDLER)
            .method("execute")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
