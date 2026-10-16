package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.config.ConfigWatcher;

/**
 * Reloads the YAML config when it changes and retransforms already-loaded classes. Enabled with
 * {@code watch=true} alongside a {@code config} path.
 */
public class ConfigWatchPlugin implements WeaverPlugin {

  static final String NAME = "config-watch";

  private ConfigWatcher watcher;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    return context.getConfigBoolean("watch", false) && context.getConfig("config") != null;
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(), so this is reached even when nothing asked for config
    // watching. A watcher without a config path has nothing to watch and fails on start.
    if (!isEnabled(context)) {
      return;
    }
    watcher = new ConfigWatcher(context.getConfig("config"), context.getRegistry());
    watcher.start();
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // watches config, registers nothing of its own
  }

  @Override
  public void destroy() {
    if (watcher != null) {
      watcher.stop();
    }
  }
}
