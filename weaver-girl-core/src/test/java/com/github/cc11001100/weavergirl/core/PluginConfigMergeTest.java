package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.github.cc11001100.weavergirl.core.config.WeaverConfig;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How the YAML file and the agent arguments are combined into the map plugins read. */
class PluginConfigMergeTest {

  @Test
  void yamlPluginSectionsAndCapabilityKeysReachThePluginMap() {
    WeaverConfig yaml = new WeaverConfig();
    Map<String, Object> jdbc = new HashMap<>();
    jdbc.put("slowQueryThreshold", 1000);
    Map<String, Object> plugins = new HashMap<>();
    plugins.put("jdbc", jdbc);
    yaml.setPlugins(plugins);
    yaml.getPluginOptions().put("metricsPort", "9400");

    Map<String, String> merged =
        WeaverGirl.mergePluginConfig(new HashMap<String, String>(), yaml);

    assertEquals("1000", merged.get("plugins.jdbc.slowQueryThreshold"));
    assertEquals("9400", merged.get("metricsPort"));
  }

  @Test
  void agentArgumentsWinOverAnEqualYamlKey() {
    WeaverConfig yaml = new WeaverConfig();
    Map<String, Object> jdbc = new HashMap<>();
    jdbc.put("slowQueryThreshold", 1000);
    Map<String, Object> plugins = new HashMap<>();
    plugins.put("jdbc", jdbc);
    yaml.setPlugins(plugins);
    yaml.getPluginOptions().put("metricsPort", "9400");

    Map<String, String> args = new HashMap<>();
    args.put("metricsPort", "9500");
    args.put("plugins.jdbc.slowQueryThreshold", "500");

    Map<String, String> merged = WeaverGirl.mergePluginConfig(args, yaml);

    assertEquals("9500", merged.get("metricsPort"));
    assertEquals("500", merged.get("plugins.jdbc.slowQueryThreshold"));
  }

  @Test
  void yamlPluginListsAreFoldedIntoTheMapWithoutOverridingArguments() {
    WeaverConfig yaml = new WeaverConfig();
    yaml.setDisabledPlugins(Arrays.asList("servlet", "kafka"));

    Map<String, String> fromYaml = WeaverGirl.mergePluginConfig(null, yaml);
    assertEquals("servlet,kafka", fromYaml.get("disabledPlugins"));

    Map<String, String> args = new HashMap<>();
    args.put("disabledPlugins", "grpc");
    Map<String, String> merged = WeaverGirl.mergePluginConfig(args, yaml);
    assertEquals("grpc", merged.get("disabledPlugins"));
    assertFalse(merged.containsKey("enabledPlugins"));
  }

  @Test
  void aNullYamlConfigStillReturnsTheArgumentsAsAMutableMap() {
    Map<String, String> args = new HashMap<>();
    args.put("apiPort", "9401");

    Map<String, String> merged = WeaverGirl.mergePluginConfig(args, null);

    assertEquals("9401", merged.get("apiPort"));
    merged.put("added", "later");
    assertEquals("later", merged.get("added"));
  }
}
