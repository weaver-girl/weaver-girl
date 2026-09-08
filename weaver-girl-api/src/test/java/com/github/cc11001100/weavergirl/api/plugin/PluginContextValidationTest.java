package com.github.cc11001100.weavergirl.api.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for PluginContext config validation default methods. */
class PluginContextValidationTest {

  private PluginContext createContext(String pluginName, Map<String, String> config) {
    return new PluginContext() {
      @Override
      public InterceptorRegistry getRegistry() {
        return null;
      }

      @Override
      public String getConfig(String key) {
        return config.get(key);
      }

      @Override
      public String getConfig(String key, String defaultValue) {
        return config.containsKey(key) ? config.get(key) : defaultValue;
      }

      @Override
      public Map<String, String> getAllConfig() {
        return config;
      }

      @Override
      public String getPluginName() {
        return pluginName;
      }
    };
  }

  @Test
  void getConfigLong_validValue_returnsParsedLong() {
    PluginContext ctx = createContext("test", mapOf("threshold", "5000"));
    assertEquals(5000L, ctx.getConfigLong("threshold", 1000));
  }

  @Test
  void getConfigLong_missingKey_returnsDefault() {
    PluginContext ctx = createContext("test", new HashMap<String, String>());
    assertEquals(1000L, ctx.getConfigLong("threshold", 1000));
  }

  @Test
  void getConfigLong_invalidValue_warnsAndReturnsDefault() {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream oldErr = System.err;
    System.setErr(new PrintStream(baos));
    try {
      PluginContext ctx = createContext("test-plugin", mapOf("threshold", "abc"));
      assertEquals(1000L, ctx.getConfigLong("threshold", 1000));
      String output = baos.toString();
      assertTrue(output.contains("WARNING"));
      assertTrue(output.contains("threshold=abc"));
      assertTrue(output.contains("test-plugin"));
    } finally {
      System.setErr(oldErr);
    }
  }

  @Test
  void getConfigInt_validValue_returnsParsedInt() {
    PluginContext ctx = createContext("test", mapOf("maxLen", "200"));
    assertEquals(200, ctx.getConfigInt("maxLen", 100));
  }

  @Test
  void getConfigInt_invalidValue_warnsAndReturnsDefault() {
    PluginContext ctx = createContext("test", mapOf("maxLen", "xyz"));
    assertEquals(100, ctx.getConfigInt("maxLen", 100));
  }

  @Test
  void getConfigBoolean_trueValue_returnsTrue() {
    PluginContext ctx = createContext("test", mapOf("enabled", "true"));
    assertTrue(ctx.getConfigBoolean("enabled", false));
  }

  @Test
  void getConfigBoolean_falseValue_returnsFalse() {
    PluginContext ctx = createContext("test", mapOf("enabled", "false"));
    assertFalse(ctx.getConfigBoolean("enabled", true));
  }

  @Test
  void getConfigBoolean_caseInsensitive() {
    PluginContext ctx = createContext("test", mapOf("enabled", "TRUE"));
    assertTrue(ctx.getConfigBoolean("enabled", false));
  }

  @Test
  void getConfigBoolean_invalidValue_warnsAndReturnsDefault() {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream oldErr = System.err;
    System.setErr(new PrintStream(baos));
    try {
      PluginContext ctx = createContext("test-plugin", mapOf("enabled", "yes"));
      assertTrue(ctx.getConfigBoolean("enabled", true));
      String output = baos.toString();
      assertTrue(output.contains("WARNING"));
      assertTrue(output.contains("enabled=yes"));
    } finally {
      System.setErr(oldErr);
    }
  }

  @Test
  void getConfigEnum_validValue_returnsValue() {
    PluginContext ctx = createContext("test", mapOf("level", "DEBUG"));
    assertEquals("DEBUG", ctx.getConfigEnum("level", "INFO", "DEBUG", "INFO", "WARN"));
  }

  @Test
  void getConfigEnum_caseInsensitive() {
    PluginContext ctx = createContext("test", mapOf("level", "debug"));
    assertEquals("DEBUG", ctx.getConfigEnum("level", "INFO", "DEBUG", "INFO", "WARN"));
  }

  @Test
  void getConfigEnum_invalidValue_warnsAndReturnsDefault() {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream oldErr = System.err;
    System.setErr(new PrintStream(baos));
    try {
      PluginContext ctx = createContext("test-plugin", mapOf("level", "TRACE"));
      assertEquals("INFO", ctx.getConfigEnum("level", "INFO", "DEBUG", "INFO", "WARN"));
      String output = baos.toString();
      assertTrue(output.contains("WARNING"));
      assertTrue(output.contains("level=TRACE"));
    } finally {
      System.setErr(oldErr);
    }
  }

  private Map<String, String> mapOf(String... keyValues) {
    Map<String, String> map = new HashMap<String, String>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    return map;
  }
}
