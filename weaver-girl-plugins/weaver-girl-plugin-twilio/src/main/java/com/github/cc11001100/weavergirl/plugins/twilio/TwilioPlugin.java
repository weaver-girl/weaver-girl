package com.github.cc11001100.weavergirl.plugins.twilio;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Twilio instrumentation plugin. Times an API call being made.
 *
 * <p>Every Twilio request is a subclass of one of {@code Creator}, {@code Deleter}, {@code
 * Fetcher}, {@code Reader}, or {@code Updater}, and each performs its call in a method of that
 * name, so the five bases are matched by superclass.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowCallThreshold} — Slow call threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class TwilioPlugin extends AbstractPlugin {

  private long slowCallThresholdMs = 500;
  private boolean enabled = true;

  private static final String[][] BASES = {
    {"com.twilio.base.Creator", "create"},
    {"com.twilio.base.Deleter", "delete"},
    {"com.twilio.base.Fetcher", "fetch"},
    {"com.twilio.base.Reader", "read"},
    {"com.twilio.base.Updater", "update"},
    {"com.twilio.base.Creator", "createAsync"},
    {"com.twilio.base.Deleter", "deleteAsync"},
    {"com.twilio.base.Reader", "readAsync"},
    {"com.twilio.base.Fetcher", "fetchAsync"},
    {"com.twilio.base.Updater", "updateAsync"}
  };

  @Override
  public String name() {
    return "twilio";
  }

  @Override
  public String instrumentedLibrary() {
    return "com.twilio.base.Creator";
  }

  @Override
  public void init(PluginContext context) {
    slowCallThresholdMs = context.getConfigLong("slowCallThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-twilio", "twilio-error", slowCallThresholdMs);

    for (String[] base : BASES) {
      registry.register(
          interceptSubclassOf(base[0])
              .method(base[1])
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
