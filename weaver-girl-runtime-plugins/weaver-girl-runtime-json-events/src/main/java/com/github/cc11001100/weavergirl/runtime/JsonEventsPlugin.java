package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.event.JsonEventListener;

/**
 * Prints every interceptor event as one JSON line. Enabled with {@code jsonEvents=true}.
 *
 * <p>This used to be wired directly into the agent entry point. It registers no interceptors; it only
 * subscribes to the event bus, which is exactly the kind of capability a plugin can carry.
 */
public class JsonEventsPlugin implements WeaverPlugin {

  static final String NAME = "json-events";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    return context.getConfigBoolean("jsonEvents", false);
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(). Subscribing when the capability is off would print JSON for
    // every event whether or not anyone asked for it.
    if (!isEnabled(context)) {
      return;
    }
    InterceptorEventPublisher.getInstance().addListener(new JsonEventListener());
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // event subscriber only
  }
}
