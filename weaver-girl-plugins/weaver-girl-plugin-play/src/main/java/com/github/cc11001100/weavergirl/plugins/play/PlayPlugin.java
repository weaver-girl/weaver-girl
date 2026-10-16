package com.github.cc11001100.weavergirl.plugins.play;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Play Framework instrumentation plugin. Times an action handling a request.
 *
 * <p>{@code Action} is the Scala interface every controller action implements and {@code apply} is
 * the method that takes the request and returns a future, so it is matched by interface.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 1000)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class PlayPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 1000;
  private boolean enabled = true;

  private static final String ACTION = "play.api.mvc.Action";

  @Override
  public String name() {
    return "play";
  }

  @Override
  public String instrumentedLibrary() {
    return "play.api.mvc.Action";
  }

  @Override
  public void init(PluginContext context) {
    slowRequestThresholdMs = context.getConfigLong("slowRequestThreshold", 1000);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-play", "play-error", slowRequestThresholdMs);

    registry.register(
        interceptImplementing(ACTION)
            .method("apply")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
