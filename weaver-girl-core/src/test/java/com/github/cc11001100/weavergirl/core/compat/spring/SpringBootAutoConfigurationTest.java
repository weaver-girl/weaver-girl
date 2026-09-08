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
}
