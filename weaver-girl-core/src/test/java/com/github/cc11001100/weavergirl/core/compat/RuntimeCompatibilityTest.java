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

  /**
   * {@code detectAll()} runs once, from the static initializer, on whatever this JVM happens to be, so
   * the version parser's old {@code 1.x} form and its fallback, and the GraalVM branch, never run there.
   * The methods are private; reflection reaches them without reloading the class.
   */
  @Test
  void versionParsingAndFrameworkLookupCoverTheBranchesTheInitializerMisses() throws Exception {
    java.lang.reflect.Method parse =
        RuntimeCompatibility.class.getDeclaredMethod("parseMajorVersion", String.class);
    parse.setAccessible(true);
    assertEquals(8, parse.invoke(null, "1.8"));
    assertEquals(11, parse.invoke(null, "11"));
    assertEquals(8, parse.invoke(null, "not-a-number"));

    java.lang.reflect.Method detect =
        RuntimeCompatibility.class.getDeclaredMethod("detectFramework", String.class, String.class);
    detect.setAccessible(true);
    // A class that is not loadable records the framework as absent.
    detect.invoke(null, "Absent Framework", "com.example.DoesNotExist");
    assertFalse(RuntimeCompatibility.hasCapability("absent-framework"));
    // Spring reports its version through a static getVersion(); a class without one is swallowed.
    detect.invoke(null, "Spring Boot", Versioned.class.getName());
    assertEquals("1.2.3", RuntimeCompatibility.getFrameworkVersions().get("Spring Boot"));
    detect.invoke(null, "Spring Framework", Versioned.class.getName());
    assertEquals("1.2.3", RuntimeCompatibility.getFrameworkVersions().get("Spring Framework"));
    detect.invoke(null, "Spring Boot", RuntimeCompatibilityTest.class.getName());

    java.lang.reflect.Method detectAll =
        RuntimeCompatibility.class.getDeclaredMethod("detectAll");
    detectAll.setAccessible(true);
    System.setProperty("org.graalvm.nativeimage.imagecode", "runtime");
    try {
      detectAll.invoke(null);
      assertTrue(RuntimeCompatibility.isNativeImage());
    } finally {
      System.clearProperty("org.graalvm.nativeimage.imagecode");
      detectAll.invoke(null);
    }
    assertFalse(RuntimeCompatibility.isNativeImage());

    // detectFramework recorded a framework this classpath does not have. Drop it so later tests see the
    // same maps the static initializer built.
    forget("CAPABILITIES", "absent-framework");
    forget("FRAMEWORK_VERSIONS", "Spring Boot");
    forget("FRAMEWORK_VERSIONS", "Spring Framework");
  }

  private static void forget(String field, String key) throws ReflectiveOperationException {
    java.lang.reflect.Field map = RuntimeCompatibility.class.getDeclaredField(field);
    map.setAccessible(true);
    ((Map<?, ?>) map.get(null)).remove(key);
  }

  /** Stands in for a framework that publishes its version from a static method. */
  public static class Versioned {
    public static String getVersion() {
      return "1.2.3";
    }
  }
}
