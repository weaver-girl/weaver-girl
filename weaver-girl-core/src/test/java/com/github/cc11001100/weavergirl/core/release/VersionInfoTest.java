package com.github.cc11001100.weavergirl.core.release;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VersionInfoTest {

  @Test
  void parseJson_fullFields() {
    String json =
        "{\"version\":\"1.2.0\","
            + "\"downloadUrl\":\"https://example.com/agent-1.2.0.jar\","
            + "\"sha256\":\"abc123\",\"mandatory\":true,"
            + "\"releaseNotes\":\"bug fixes\"}";
    VersionInfo info = VersionInfo.parseJson(json);

    assertNotNull(info);
    assertEquals("1.2.0", info.getVersion());
    assertEquals("https://example.com/agent-1.2.0.jar", info.getDownloadUrl());
    assertEquals("abc123", info.getSha256());
    assertTrue(info.isMandatory());
    assertEquals("bug fixes", info.getReleaseNotes());
    assertTrue(info.isDownloadable());
  }

  @Test
  void parseJson_minimalVersionOnly() {
    VersionInfo info = VersionInfo.parseJson("{\"version\":\"2.0.0\"}");
    assertNotNull(info);
    assertEquals("2.0.0", info.getVersion());
    assertFalse(info.isDownloadable());
    assertFalse(info.isMandatory());
  }

  @Test
  void isDownloadable_rejectsEmptyAndWhitespaceUrls() {
    assertFalse(VersionInfo.builder().version("1.0.0").downloadUrl("").build().isDownloadable());
    assertFalse(
        VersionInfo.builder().version("1.0.0").downloadUrl("   ").build().isDownloadable());
  }

  @Test
  void parseJson_missingVersion_returnsNull() {
    assertNull(VersionInfo.parseJson("{\"downloadUrl\":\"https://x/y.jar\"}"));
  }

  @Test
  void parseJson_nullAndEmpty_returnsNull() {
    assertNull(VersionInfo.parseJson(null));
    assertNull(VersionInfo.parseJson(""));
    assertNull(VersionInfo.parseJson("   "));
    assertNull(VersionInfo.parseJson("not json at all"));
  }

  @Test
  void parseJson_ignoresUnknownFields() {
    VersionInfo info =
        VersionInfo.parseJson("{\"version\":\"1.1.0\",\"futureField\":123,\"nested\":{\"a\":1}}");
    assertNotNull(info);
    assertEquals("1.1.0", info.getVersion());
  }

  @Test
  void parseJson_handlesStringParserEdgeCases() {
    assertNull(VersionInfo.parseJson("{\"version\"}"));
    assertNull(VersionInfo.parseJson("{\"version\":}"));
    assertNull(VersionInfo.parseJson("{\"version\":123}"));
    assertNull(VersionInfo.parseJson("{\"version\":\"1.0.0}"));

    VersionInfo escaped =
        VersionInfo.parseJson(
            "{\"version\":\"1.0\\\".0\",\"downloadUrl\":\"https:\\/\\/example\"}");
    assertNotNull(escaped);
    assertEquals("1.0\".0", escaped.getVersion());
    assertEquals("https://example", escaped.getDownloadUrl());
  }

  @Test
  void parseJson_handlesLiteralEdgeCases() {
    assertFalse(VersionInfo.parseJson("{\"version\":\"1.0.0\",\"mandatory\":}").isMandatory());
    assertFalse(
        VersionInfo.parseJson("{\"version\":\"1.0.0\",\"mandatory\"}").isMandatory());
    assertFalse(
        VersionInfo.parseJson("{\"version\":\"1.0.0\",\"mandatory\":false}").isMandatory());
    assertTrue(
        VersionInfo.parseJson("{\"version\":\"1.0.0\",\"mandatory\": TRUE }").isMandatory());
  }

  @Test
  void compareVersions_ordering() {
    assertEquals(0, VersionInfo.compareVersions("1.0.0", "1.0.0"));
    assertTrue(VersionInfo.compareVersions("2.0.0", "1.9.9") > 0);
    assertTrue(VersionInfo.compareVersions("1.0.0", "1.0.1") < 0);
    assertTrue(VersionInfo.compareVersions("1.10.0", "1.9.0") > 0);
    assertTrue(VersionInfo.compareVersions("1.0", "1.0.0") == 0);
  }

  @Test
  void compareVersions_releaseOutranksPrerelease() {
    assertTrue(VersionInfo.compareVersions("1.0.0", "1.0.0-SNAPSHOT") > 0);
    assertTrue(VersionInfo.compareVersions("1.0.0-SNAPSHOT", "1.0.0") < 0);
  }

  @Test
  void compareVersions_nullTreatedAsZero() {
    assertTrue(VersionInfo.compareVersions(null, "0.0.1") < 0);
    assertTrue(VersionInfo.compareVersions("0.0.1", null) > 0);
  }

  @Test
  void compareVersions_handlesMetadataAndMalformedParts() {
    assertEquals(0, VersionInfo.compareVersions("1.0.0+build1", "1.0.0+build2"));
    assertEquals(0, VersionInfo.compareVersions("x.0.0", "0.0.0"));
    assertTrue(VersionInfo.compareVersions("1.0.0", "1.0.0.0") == 0);
  }

  @Test
  void isNewerThan_strictlyNewer() {
    assertTrue(VersionInfo.isNewerThan("1.0.1", "1.0.0"));
    assertFalse(VersionInfo.isNewerThan("1.0.0", "1.0.0"));
    assertFalse(VersionInfo.isNewerThan("0.9.9", "1.0.0"));
  }

  @Test
  void builder_requiresVersion() {
    assertThrows(IllegalStateException.class, () -> VersionInfo.builder().build());
  }

  @Test
  void equalsAndHashCode() {
    VersionInfo a = VersionInfo.builder().version("1.0.0").build();
    VersionInfo b = VersionInfo.builder().version("1.0.0").build();
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, VersionInfo.builder().version("1.0.1").build());
    assertNotEquals(a, null);
    assertNotEquals(a, "1.0.0");
    assertEquals(a, a);

    VersionInfo full =
        VersionInfo.builder()
            .version("1.0.0")
            .downloadUrl("https://example")
            .sha256("abc")
            .mandatory(true)
            .releaseNotes("notes")
            .build();
    assertNotEquals(full, VersionInfo.builder().version("1.0.0").downloadUrl("other").sha256("abc").mandatory(true).releaseNotes("notes").build());
    assertNotEquals(full, VersionInfo.builder().version("1.0.0").downloadUrl("https://example").sha256("other").mandatory(true).releaseNotes("notes").build());
    assertNotEquals(full, VersionInfo.builder().version("1.0.0").downloadUrl("https://example").sha256("abc").mandatory(false).releaseNotes("notes").build());
    assertNotEquals(full, VersionInfo.builder().version("1.0.0").downloadUrl("https://example").sha256("abc").mandatory(true).releaseNotes("other").build());
  }

  @Test
  void toString_containsVersion() {
    assertTrue(VersionInfo.builder().version("3.1.4").build().toString().contains("3.1.4"));
  }
}
