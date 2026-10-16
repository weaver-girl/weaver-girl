package com.github.cc11001100.weavergirl.core.compat.spring;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.*;

/** Tests for Spring Boot auto-configuration (P50). */
class SpringBootAutoConfigurationTest {

  @Test
  void getExclusionPatterns_returnsNonEmpty() {
    String[] patterns = SpringBootAutoConfiguration.getExclusionPatterns();
    assertTrue(patterns.length > 0);
    // Verify common Spring patterns are included
    boolean hasSpringPattern = false;
    for (String p : patterns) {
      if (p.contains("org.springframework")) {
        hasSpringPattern = true;
        break;
      }
    }
    assertTrue(hasSpringPattern);
  }

  @Test
  void getExclusionPatterns_returnsClone() {
    String[] p1 = SpringBootAutoConfiguration.getExclusionPatterns();
    String[] p2 = SpringBootAutoConfiguration.getExclusionPatterns();
    assertNotSame(p1, p2);
    assertEquals(p1.length, p2.length);
  }

  @Test
  void getRecommendedConfig_returnsMap() {
    Map<String, String> config = SpringBootAutoConfiguration.getRecommendedConfig();
    // In test environment without Spring Boot, may or may not have entries
    assertNotNull(config);
  }

  /** With Spring Boot and a reactive stack present, the recommendation covers both. */
  @Test
  void aDetectedSpringBootAndReactiveStackShapeTheConfig() throws Exception {
    java.lang.reflect.Field capabilities =
        com.github.cc11001100.weavergirl.core.compat.RuntimeCompatibility.class.getDeclaredField(
            "CAPABILITIES");
    @SuppressWarnings("unchecked")
    java.util.Map<String, Boolean> detected =
        (java.util.Map<String, Boolean>)
            com.github.cc11001100.weavergirl.core.testsupport.Statics.read(capabilities, null);
    Boolean spring = detected.get("spring-boot");
    Boolean reactor = detected.get("reactor");
    Boolean webflux = detected.get("webflux");
    try {
      detected.put("spring-boot", true);
      detected.put("reactor", true);

      assertTrue(SpringBootAutoConfiguration.shouldApply());
      Map<String, String> config = SpringBootAutoConfiguration.getRecommendedConfig();
      assertTrue(config.get("excludedClasses").contains("org.springframework"));
      assertEquals("10", config.get("samplingRate"));
      assertEquals("20000", config.get("samplingThreshold"));
    } finally {
      restore(detected, "spring-boot", spring);
      restore(detected, "reactor", reactor);
      restore(detected, "webflux", webflux);
    }
  }

  private static void restore(java.util.Map<String, Boolean> map, String key, Boolean value) {
    if (value == null) {
      map.remove(key);
    } else {
      map.put(key, value);
    }
  }
}
