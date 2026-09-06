package com.github.cc11001100.weavergirl.core.release;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionInfoTest {

    @Test
    void parseJson_fullFields() {
        String json = "{\"version\":\"1.2.0\","
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
        VersionInfo info = VersionInfo.parseJson(
                "{\"version\":\"1.1.0\",\"futureField\":123,\"nested\":{\"a\":1}}");
        assertNotNull(info);
        assertEquals("1.1.0", info.getVersion());
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
    }

    @Test
    void toString_containsVersion() {
        assertTrue(VersionInfo.builder().version("3.1.4").build().toString().contains("3.1.4"));
    }
}
