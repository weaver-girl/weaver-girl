package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.management.AgentMonitor;

/**
 * Publishes the agent status MBean. Enabled unless {@code jmx=false}.
 *
 * <p>This registers no interceptors. It registers the MBean when it starts and removes it when it
 * stops, so leaving the jar out of a distribution leaves no MBean behind.
 */
public class JmxPlugin implements WeaverPlugin {

  static final String NAME = "jmx";

  private boolean registered;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    return context.getConfigBoolean("jmx", true);
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(). With jmx switched off there is nothing to publish.
    if (!isEnabled(context)) {
      return;
    }
    // AgentMonitor carries the live counters the engine collects. Registering it here, and only here,
    // is what makes jmx=false actually leave no MBean behind.
    AgentMonitor.getInstance().register();
    registered = true;
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // publishes an MBean, registers nothing of its own
  }

  @Override
  public void destroy() {
    if (registered) {
      AgentMonitor.getInstance().unregister();
      registered = false;
    }
  }
}
