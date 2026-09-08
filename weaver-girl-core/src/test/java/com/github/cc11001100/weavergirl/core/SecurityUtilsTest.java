package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for SecurityUtils. */
class SecurityUtilsTest {

  @Test
  void detectsSensitiveKeys() {
    assertTrue(SecurityUtils.isSensitiveKey("password"));
    assertTrue(SecurityUtils.isSensitiveKey("Password"));
    assertTrue(SecurityUtils.isSensitiveKey("db.password"));
    assertTrue(SecurityUtils.isSensitiveKey("redis.secret"));
    assertTrue(SecurityUtils.isSensitiveKey("api_key"));
    assertTrue(SecurityUtils.isSensitiveKey("apiKey"));
    assertTrue(SecurityUtils.isSensitiveKey("access-token"));
    assertTrue(SecurityUtils.isSensitiveKey("Authorization"));
  }

  @Test
  void nonSensitiveKeysPassThrough() {
    assertFalse(SecurityUtils.isSensitiveKey("host"));
    assertFalse(SecurityUtils.isSensitiveKey("port"));
    assertFalse(SecurityUtils.isSensitiveKey("timeout"));
    assertFalse(SecurityUtils.isSensitiveKey("pluginName"));
    assertFalse(SecurityUtils.isSensitiveKey("samplingRate"));
  }

  @Test
  void nullKeyIsNotSensitive() {
    assertFalse(SecurityUtils.isSensitiveKey(null));
  }

  @Test
  void masksSensitiveValues() {
    assertEquals("****", SecurityUtils.maskIfSensitive("password", "mySecret123"));
    assertEquals("****", SecurityUtils.maskIfSensitive("db.password", "secret"));
    assertEquals("****", SecurityUtils.maskIfSensitive("token", "abc.def.ghi"));
  }

  @Test
  void preservesNonSensitiveValues() {
    assertEquals("localhost", SecurityUtils.maskIfSensitive("host", "localhost"));
    assertEquals("3306", SecurityUtils.maskIfSensitive("port", "3306"));
    assertEquals("true", SecurityUtils.maskIfSensitive("enabled", "true"));
  }

  @Test
  void nullValueReturnsNull() {
    assertNull(SecurityUtils.maskIfSensitive("password", null));
    assertNull(SecurityUtils.maskIfSensitive("host", null));
  }

  @Test
  void sanitizeMapMasksAllSensitive() {
    Map<String, String> config = new HashMap<>();
    config.put("host", "localhost");
    config.put("port", "3306");
    config.put("password", "superSecret");
    config.put("db.user", "admin");
    config.put("db.password", "alsoSecret");

    Map<String, String> sanitized = SecurityUtils.sanitizeForLogging(config);

    assertEquals("localhost", sanitized.get("host"));
    assertEquals("3306", sanitized.get("port"));
    assertEquals("****", sanitized.get("password"));
    assertEquals("admin", sanitized.get("db.user"));
    assertEquals("****", sanitized.get("db.password"));
  }

  @Test
  void sanitizeNullReturnsEmpty() {
    assertTrue(SecurityUtils.sanitizeForLogging(null).isEmpty());
  }

  @Test
  void validatePathRejectsTraversal() {
    assertThrows(
        SecurityException.class, () -> SecurityUtils.validatePath("../../etc/passwd", "/opt/app"));
  }

  @Test
  void validatePathAcceptsValidPath() {
    String result = SecurityUtils.validatePath("/opt/app/config.yml", "/opt/app");
    assertTrue(result.startsWith("/opt/app"));
  }
}
