package com.github.cc11001100.weavergirl.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeaverGirlAgentTest {

  /** Access the private parseAgentArgs method via reflection for testing. */
  @SuppressWarnings("unchecked")
  private Map<String, String> invokeParseAgentArgs(String args) throws Exception {
    Method method = WeaverGirlAgent.class.getDeclaredMethod("parseAgentArgs", String.class);
    method.setAccessible(true);
    return (Map<String, String>) method.invoke(null, args);
  }

  @Test
  void parseConfigPath() throws Exception {
    Map<String, String> result = invokeParseAgentArgs("config=/path/to/weaver.yml");
    assertEquals("/path/to/weaver.yml", result.get("config"));
  }

  @Test
  void parsePluginsDir() throws Exception {
    Map<String, String> result = invokeParseAgentArgs("plugins=/path/to/dir");
    assertEquals("/path/to/dir", result.get("plugins"));
  }

  @Test
  void parseConfigAndWatchUsingKeyValueFormat() throws Exception {
    // When the string does NOT start with "config=", it uses key=value parsing.
    // To get both parsed, use a format that doesn't start with "config=":
    // e.g., "watch=true,config=/path/to/weaver.yml"
    Map<String, String> result = invokeParseAgentArgs("watch=true,config=/path/to/weaver.yml");
    assertEquals("true", result.get("watch"));
    assertEquals("/path/to/weaver.yml", result.get("config"));
  }

  @Test
  void parseConfigLegacyFormat() throws Exception {
    // When the string starts with "config=", the legacy handler takes the whole remainder
    Map<String, String> result = invokeParseAgentArgs("config=/path/to/weaver.yml");
    assertEquals("/path/to/weaver.yml", result.get("config"));
  }

  @Test
  void parseEmptyString() throws Exception {
    Map<String, String> result = invokeParseAgentArgs("");
    assertTrue(result.isEmpty());
  }

  @Test
  void parseNull() throws Exception {
    Map<String, String> result = invokeParseAgentArgs(null);
    assertTrue(result.isEmpty());
  }

  @Test
  void parseWatchWithoutConfig() throws Exception {
    Map<String, String> result = invokeParseAgentArgs("watch=true");
    assertEquals("true", result.get("watch"));
    assertNull(result.get("config"));
  }

  @Test
  void parseApiPort() throws Exception {
    Map<String, String> result = invokeParseAgentArgs("apiPort=9402");
    assertEquals("9402", result.get("apiPort"));
  }

  @Test
  void parseApiPortWithOtherArgs() throws Exception {
    Map<String, String> result =
        invokeParseAgentArgs("config=/path/weaver.yml,apiPort=9402,watch=true");
    assertEquals("9402", result.get("apiPort"));
    assertEquals("/path/weaver.yml", result.get("config"));
    assertEquals("true", result.get("watch"));
  }
}
