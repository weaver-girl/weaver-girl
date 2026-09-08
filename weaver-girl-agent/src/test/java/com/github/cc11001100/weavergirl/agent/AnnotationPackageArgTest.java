package com.github.cc11001100.weavergirl.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for the annotationPackages agent argument parsing. */
class AnnotationPackageArgTest {

  /** Access the private parseAgentArgs method via reflection. */
  @SuppressWarnings("unchecked")
  private Map<String, String> parseArgs(String agentArgs) throws Exception {
    Method parseMethod = WeaverGirlAgent.class.getDeclaredMethod("parseAgentArgs", String.class);
    parseMethod.setAccessible(true);
    return (Map<String, String>) parseMethod.invoke(null, agentArgs);
  }

  @Test
  void parseAgentArgs_annotationPackages_shouldBeParsedCorrectly() throws Exception {
    Map<String, String> args =
        parseArgs(
            "config=/path/to/weaver.yml,annotationPackages=com.example.interceptors;com.example.aspects");

    assertEquals("/path/to/weaver.yml", args.get("config"));
    assertEquals("com.example.interceptors;com.example.aspects", args.get("annotationPackages"));
  }

  @Test
  void parseAgentArgs_singleAnnotationPackage_shouldWork() throws Exception {
    Map<String, String> args = parseArgs("annotationPackages=com.example.interceptors");

    assertEquals("com.example.interceptors", args.get("annotationPackages"));
  }

  @Test
  void parseAgentArgs_noAnnotationPackages_shouldNotHaveKey() throws Exception {
    Map<String, String> args = parseArgs("config=/path/to/weaver.yml");

    assertNull(
        args.get("annotationPackages"), "annotationPackages should be null when not specified");
  }

  @Test
  void parseAgentArgs_emptyAnnotationPackages_shouldBeEmpty() throws Exception {
    Map<String, String> args = parseArgs("annotationPackages=");

    String value = args.get("annotationPackages");
    // Empty value after = is parsed as empty string
    assertTrue(
        value == null || value.isEmpty(),
        "annotationPackages should be null or empty when specified without a value");
  }
}
