package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.runtime.release.UpdateCheck;

/**
 * Polls a version endpoint and reports when a newer agent is available. Enabled with {@code
 * updateCheckEndpoint}; with {@code updateAutoStage=true} a published artifact is downloaded and
 * verified into a staging directory for an operator to swap in.
 *
 * <p>This registers no interceptors. It only starts the checker when the endpoint is set, so shipping
 * the jar costs nothing until then. A loaded agent jar cannot replace itself, so the staged file is
 * never applied automatically.
 */
public class UpdateCheckPlugin implements WeaverPlugin {

  static final String NAME = "update-check";

  private UpdateCheck updateCheck;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    String endpoint = context.getConfig("updateCheckEndpoint");
    return endpoint != null && !endpoint.trim().isEmpty();
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(). Without an endpoint there is nothing to poll.
    if (!isEnabled(context)) {
      return;
    }
    updateCheck = new UpdateCheck();
    updateCheck.start(context.getAllConfig());
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // version polling only
  }

  @Override
  public void destroy() {
    if (updateCheck != null) {
      updateCheck.stop();
    }
  }
}
