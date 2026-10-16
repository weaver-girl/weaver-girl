package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.AgentInstrumentation;
import com.github.cc11001100.weavergirl.core.taint.TaintInstrumentation;
import java.lang.instrument.Instrumentation;

/**
 * Tracks taint through {@code String}, {@code StringBuilder} and process execution. Enabled with
 * {@code taint=true}.
 *
 * <p>This registers no interceptors of its own. The advice it installs goes on JDK classes, which the
 * ordinary transformer never visits, and it needs the {@link Instrumentation} the agent publishes
 * through {@link AgentInstrumentation}. Off by default: instrumenting {@code String} on every launch
 * is not free, and nothing reads the taint unless an application asks for it.
 */
public class TaintPlugin implements WeaverPlugin {

  static final String NAME = "taint";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    return context.getConfigBoolean("taint", false);
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(). With taint switched off there is nothing to instrument.
    if (!isEnabled(context)) {
      return;
    }
    Instrumentation instrumentation = AgentInstrumentation.get();
    if (instrumentation == null) {
      context
          .getLogger()
          .warn("taint=true but the agent published no Instrumentation; taint tracking not installed");
      return;
    }
    TaintInstrumentation.install(instrumentation);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // instruments JDK classes directly, registers nothing of its own
  }

  @Override
  public void destroy() {
    // The advice is woven into JDK classes and cannot be removed without redefining them.
  }
}
