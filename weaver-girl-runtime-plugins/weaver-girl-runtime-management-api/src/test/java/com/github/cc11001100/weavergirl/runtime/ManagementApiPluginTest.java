package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The management API switches on only when {@code apiPort} is set, and stops on destroy. */
class ManagementApiPluginTest {

  @Test
  void disabledWithoutPort() {
    assertFalse(new ManagementApiPlugin().isEnabled(context(Collections.emptyMap())));
  }

  @Test
  void enabledWhenPortSet() {
    assertTrue(new ManagementApiPlugin().isEnabled(context(config("apiPort", "0"))));
  }

  @Test
  void startsAndStopsOnFreePort() throws IOException {
    int port = freePort();
    ManagementApiPlugin plugin = new ManagementApiPlugin();
    PluginContext context = context(config("apiPort", Integer.toString(port)));

    plugin.init(context);
    // The port is taken while the plugin runs, and free again once it is destroyed.
    assertTrue(portTaken(port));
    plugin.destroy();
    assertFalse(portTaken(port));
  }

  @Test
  void invalidPortDoesNotThrow() {
    ManagementApiPlugin plugin = new ManagementApiPlugin();
    plugin.init(context(config("apiPort", "not-a-port")));
    plugin.destroy();
  }

  private static int freePort() throws IOException {
    ServerSocket socket = new ServerSocket(0);
    int port = socket.getLocalPort();
    socket.close();
    return port;
  }

  private static boolean portTaken(int port) {
    try {
      ServerSocket socket = new ServerSocket(port);
      socket.close();
      return false;
    } catch (IOException e) {
      return true;
    }
  }

  private static Map<String, String> config(String key, String value) {
    Map<String, String> config = new HashMap<>();
    config.put(key, value);
    return config;
  }

  private static PluginContext context(Map<String, String> config) {
    return new com.github.cc11001100.weavergirl.core.plugin.DefaultPluginContext(
        registry(), config, ManagementApiPlugin.NAME);
  }

  private static InterceptorRegistry registry() {
    return new com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry();
  }
}
