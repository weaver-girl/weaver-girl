package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for DefaultPluginContext.
 */
class DefaultPluginContextTest {

  private InterceptorRegistry mockRegistry;

  @BeforeEach
  void setUp() {
    mockRegistry = Mockito.mock(InterceptorRegistry.class);
  }

  @Test
  void constructor_withValidParameters() {
    Map<String, String> config = new HashMap<>();
    config.put("key1", "value1");

    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "test-plugin");

    assertEquals(mockRegistry, context.getRegistry());
    assertEquals("test-plugin", context.getPluginName());
  }

  @Test
  void constructor_withNullConfig_usesEmptyMap() {
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, null, "test-plugin");

    assertNotNull(context.getAllConfig());
    assertTrue(context.getAllConfig().isEmpty());
  }

  @Test
  void getConfig_fromSystemProperty_namespaced() {
    Map<String, String> config = new HashMap<>();
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "myplugin");

    System.setProperty("weavergirl.plugin.myplugin.dbUrl", "jdbc:test");

    try {
      assertEquals("jdbc:test", context.getConfig("dbUrl"));
    } finally {
      System.clearProperty("weavergirl.plugin.myplugin.dbUrl");
    }
  }

  @Test
  void getConfig_fromSystemProperty_plain() {
    Map<String, String> config = new HashMap<>();
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "myplugin");

    System.setProperty("plainKey", "plainValue");

    try {
      assertEquals("plainValue", context.getConfig("plainKey"));
    } finally {
      System.clearProperty("plainKey");
    }
  }

  @Test
  void getConfig_fromConfigMap() {
    Map<String, String> config = new HashMap<>();
    config.put("mapKey", "mapValue");
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "test-plugin");

    assertEquals("mapValue", context.getConfig("mapKey"));
  }

  @Test
  void getConfig_precedence_systemPropertyOverConfigMap() {
    Map<String, String> config = new HashMap<>();
    config.put("key", "fromMap");
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "test-plugin");

    System.setProperty("key", "fromSystem");

    try {
      assertEquals("fromSystem", context.getConfig("key"));
    } finally {
      System.clearProperty("key");
    }
  }

  @Test
  void getConfig_withDefaultValue_returnsDefaultWhenNotFound() {
    Map<String, String> config = new HashMap<>();
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "test-plugin");

    assertEquals("defaultVal", context.getConfig("nonexistent", "defaultVal"));
  }

  @Test
  void getConfig_withDefaultValue_stillReadsSystemProperties() {
    Map<String, String> config = new HashMap<>();
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "myplugin");
    System.setProperty("weavergirl.plugin.myplugin.threshold", "250");

    try {
      assertEquals("250", context.getConfig("threshold", "1000"));
    } finally {
      System.clearProperty("weavergirl.plugin.myplugin.threshold");
    }
  }

  @Test
  void getConfig_withDefaultValue_readsPluginSection() {
    Map<String, String> config = new HashMap<>();
    config.put("plugins.myplugin.threshold", "250");
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "myplugin");

    assertEquals("250", context.getConfig("threshold", "1000"));
  }

  @Test
  void getConfig_pluginSectionWinsOverAnotherPluginsGlobalKey() {
    Map<String, String> config = new HashMap<>();
    config.put("threshold", "1000");
    config.put("plugins.other.threshold", "999");
    config.put("plugins.myplugin.threshold", "250");
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "myplugin");

    assertEquals("250", context.getConfig("threshold"));
    // A different plugin is not affected by this plugin's scoped entry.
    DefaultPluginContext other = new DefaultPluginContext(mockRegistry, config, "other");
    assertEquals("999", other.getConfig("threshold"));
  }

  @Test
  void getConfig_namespacedPropertyWinsOverPluginSection() {
    Map<String, String> config = new HashMap<>();
    config.put("plugins.myplugin.threshold", "250");
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "myplugin");
    System.setProperty("weavergirl.plugin.myplugin.threshold", "42");

    try {
      assertEquals("42", context.getConfig("threshold"));
    } finally {
      System.clearProperty("weavergirl.plugin.myplugin.threshold");
    }
  }

  @Test
  void getAllConfig_returnsUnmodifiableMap() {
    Map<String, String> config = new HashMap<>();
    config.put("key", "value");
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, config, "test-plugin");

    Map<String, String> allConfig = context.getAllConfig();
    assertEquals("value", allConfig.get("key"));
    assertThrows(UnsupportedOperationException.class, () -> allConfig.put("new", "val"));
  }

  @Test
  void getPluginName_returnsCorrectName() {
    DefaultPluginContext context = new DefaultPluginContext(mockRegistry, null, "my-awesome-plugin");

    assertEquals("my-awesome-plugin", context.getPluginName());
  }
}
