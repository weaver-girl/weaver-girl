package com.github.cc11001100.weavergirl.plugins.scalafuture;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Scala Future instrumentation plugin. Times a callback a Future runs when it completes.
 *
 * <p>{@code scala.concurrent.impl.Promise$Transformation} is the class that holds one callback and
 * {@code run} is what executes it, so it is matched by class name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowCallbackThreshold} — Slow callback threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class ScalaFuturePlugin extends AbstractPlugin {

  private long thresholdMs = 200;
  private boolean enabled = true;

  @Override
  public String name() {
    return "scalafuture";
  }

  @Override
  public String instrumentedLibrary() {
    return "scala.concurrent.impl.Promise";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowCallbackThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-scalafuture", "scalafuture-error", thresholdMs);

    registry.register(
        intercept("scala.concurrent.impl.Promise$Transformation")
            .method("run")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
