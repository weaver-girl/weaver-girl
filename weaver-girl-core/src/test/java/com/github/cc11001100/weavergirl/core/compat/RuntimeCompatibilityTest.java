package com.github.cc11001100.weavergirl.core.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.*;

/** Tests for Runtime Compatibility (P50). */
class RuntimeCompatibilityTest {

  @Test
  void hasCapability_returnsFalseForUnknown() {
    assertFalse(RuntimeCompatibility.hasCapability("nonexistent-framework"));
  }

  @Test
  void getJavaMajorVersion_returnsPositiveNumber() {
    int version = RuntimeCompatibility.getJavaMajorVersion();
    assertTrue(version >= 8, "Java version should be at least 8, got " + version);
  }

  @Test
  void hasVirtualThreads_consistentWithJavaVersion() {
    boolean vthreads = RuntimeCompatibility.hasVirtualThreads();
    int version = RuntimeCompatibility.getJavaMajorVersion();
    assertEquals(version >= 21, vthreads, "Virtual Threads should be available only on Java 21+");
  }

  @Test
  void isNativeImage_falseInTest() {
    assertFalse(
        RuntimeCompatibility.isNativeImage(), "Should not be running in Native Image during tests");
  }

  @Test
  void getAllCapabilities_isNotEmpty() {
    Map<String, Boolean> caps = RuntimeCompatibility.getAllCapabilities();
    assertFalse(caps.isEmpty());
    // Java version capability should always be present
    boolean hasJavaVersion = caps.keySet().stream().anyMatch(k -> k.startsWith("java.version."));
    assertTrue(hasJavaVersion, "Should have java.version capability");
  }

  @Test
  void getFrameworkVersions_includesJava() {
    Map<String, String> versions = RuntimeCompatibility.getFrameworkVersions();
    assertTrue(versions.containsKey("java"));
    assertNotNull(versions.get("java"));
  }

  @Test
  void getSummary_containsKeyInfo() {
    String summary = RuntimeCompatibility.getSummary();
    assertTrue(summary.contains("Java:"));
    assertTrue(summary.contains("Virtual Threads:"));
    assertTrue(summary.contains("GraalVM Native:"));
  }

  @Test
  void getAllCapabilities_isImmutable() {
    Map<String, Boolean> caps = RuntimeCompatibility.getAllCapabilities();
    assertThrows(UnsupportedOperationException.class, () -> caps.put("test", true));
  }

  @Test
  void getFrameworkVersions_isImmutable() {
    Map<String, String> versions = RuntimeCompatibility.getFrameworkVersions();
    assertThrows(UnsupportedOperationException.class, () -> versions.put("test", "1.0"));
  }
}
