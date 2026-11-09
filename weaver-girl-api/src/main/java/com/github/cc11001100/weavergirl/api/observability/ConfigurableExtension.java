package com.github.cc11001100.weavergirl.api.observability;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;

/**
 * Extension point that can be dynamically enabled or disabled through configuration.
 *
 * <p>Implement this interface on any extension point to allow operators to control
 * whether it is active through configuration, without modifying the plugin code.
 *
 * <p>The agent's implementation checks {@link #isEnabled(PluginContext)} during registration
 * and only registers the extension if it returns true.
 *
 * <h3>Example</h3>
 *
 * <pre>
 * public class MySpanSink implements SpanSink, ConfigurableExtension {
 *   @Override
 *   public String name() {
 *     return "my-sink";
 *   }
 *
 *   @Override
 *   public String configKey() {
 *     return "mySink.enabled";
 *   }
 *
 *   @Override
 *   public boolean isEnabled(PluginContext context) {
 *     return context.getConfigBoolean(configKey(), true);
 *   }
 *
 *   @Override
 *   public void onSpanEnd(SpanRecord span) {
 *     // Process span
 *   }
 * }
 * </pre>
 *
 * @since 1.2.0
 */
public interface ConfigurableExtension {

  /**
   * Configuration key used to control this extension.
   *
   * <p>Should be a simple key like "mySink.enabled" or "processor.enabled".
   *
   * @return the configuration key, or null if no configuration key is used
   */
  String configKey();

  /**
   * Check whether this extension is enabled.
   *
   * <p>Called during registration. Return true to enable, false to disable.
   *
   * @param context the plugin context
   * @return true if enabled
   */
  boolean isEnabled(PluginContext context);
}
