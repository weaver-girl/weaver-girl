package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.management.AgentApiServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Serves the management REST API on {@code apiPort}. */
public class ManagementApiPlugin implements WeaverPlugin {

  private static final Logger log = LoggerFactory.getLogger(ManagementApiPlugin.class);

  static final String NAME = "management-api";

  private AgentApiServer server;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    return context.getConfig("apiPort") != null;
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(). With no apiPort there is no server to start.
    if (!isEnabled(context)) {
      return;
    }
    int port = context.getConfigInt("apiPort", -1);
    if (port < 0) {
      log.warn("Invalid apiPort value: {}, expected integer", context.getConfig("apiPort"));
      return;
    }
    try {
      server = new AgentApiServer(port);
      server.start();
      log.info("Management API started on port {}", port);
    } catch (Exception e) {
      log.warn("Failed to start management API server: {}", e.getMessage());
    }
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // HTTP endpoint only
  }

  @Override
  public void destroy() {
    if (server != null) {
      server.stop();
    }
  }
}
