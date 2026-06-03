package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.plugin.PluginMetadata;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.List;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Plugin Verifier and Metadata (P51).
 */
class PluginVerifierTest {

    private File tempJar;
    private PluginVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        verifier = new PluginVerifier();
    }

    @AfterEach
    void tearDown() {
        if (tempJar != null && tempJar.exists()) {
            tempJar.delete();
        }
    }

    // ===== PluginMetadata =====

    @Test
    void metadata_fromProperties() throws Exception {
        String props = "plugin.name=test-plugin\n"
                + "plugin.version=1.2.0\n"
                + "plugin.description=A test plugin\n"
                + "plugin.author=Test Author\n"
                + "plugin.depends=jdbc,redis\n"
                + "plugin.targetFrameworks=spring,quarkus\n"
                + "plugin.minimumAgentVersion=1.0.0\n"
                + "plugin.sha256=abc123\n";

        PluginMetadata meta = PluginMetadata.fromProperties(
                new ByteArrayInputStream(props.getBytes()));

        assertEquals("test-plugin", meta.getName());
        assertEquals("1.2.0", meta.getVersion());
        assertEquals("A test plugin", meta.getDescription());
        assertEquals("Test Author", meta.getAuthor());
        assertEquals(List.of("jdbc", "redis"), meta.getDepends());
        assertEquals(List.of("spring", "quarkus"), meta.getTargetFrameworks());
        assertEquals("1.0.0", meta.getMinimumAgentVersion());
        assertEquals("abc123", meta.getExpectedSha256());
    }

    @Test
    void metadata_defaultValues() throws Exception {
        String props = "plugin.name=minimal\n";
        PluginMetadata meta = PluginMetadata.fromProperties(
                new ByteArrayInputStream(props.getBytes()));

        assertEquals("minimal", meta.getName());
        assertEquals("0.0.0", meta.getVersion());
        assertEquals("", meta.getDescription());
        assertTrue(meta.getDepends().isEmpty());
        assertTrue(meta.getTargetFrameworks().isEmpty());
        assertNull(meta.getMinimumAgentVersion());
        assertNull(meta.getExpectedSha256());
    }

    @Test
    void metadata_builder() {
        PluginMetadata meta = PluginMetadata.builder()
                .name("builder-test")
                .version("2.0.0")
                .description("Built with builder")
                .author("Author")
                .depend("dep1")
                .depend("dep2")
                .targetFramework("fw1")
                .minimumAgentVersion("1.1.0")
                .expectedSha256("sha256hex")
                .build();

        assertEquals("builder-test", meta.getName());
        assertEquals("2.0.0", meta.getVersion());
        assertEquals(2, meta.getDepends().size());
        assertEquals(1, meta.getTargetFrameworks().size());
    }

    @Test
    void metadata_dependsAreImmutable() {
        PluginMetadata meta = PluginMetadata.builder().name("t").depend("d").build();
        assertThrows(UnsupportedOperationException.class,
                () -> meta.getDepends().add("extra"));
    }

    @Test
    void metadata_toString() {
        PluginMetadata meta = PluginMetadata.builder().name("str-test").version("1.0").build();
        String str = meta.toString();
        assertTrue(str.contains("str-test"));
        assertTrue(str.contains("1.0"));
    }

    // ===== PluginVerifier =====

    @Test
    void verify_nonExistentFile_returnsFail() {
        PluginVerifier.VerificationResult result = verifier.verify("/nonexistent/plugin.jar");
        assertFalse(result.isValid());
        assertTrue(result.getError().contains("not found"));
    }

    @Test
    void verify_jarWithoutSPI_returnsFail() throws Exception {
        tempJar = createTestJar(false, false);
        PluginVerifier.VerificationResult result = verifier.verify(tempJar.getAbsolutePath());
        assertFalse(result.isValid());
        assertTrue(result.getError().contains("SPI"));
    }

    @Test
    void verify_jarWithSPI_returnsOk() throws Exception {
        tempJar = createTestJar(true, false);
        PluginVerifier.VerificationResult result = verifier.verify(tempJar.getAbsolutePath());
        assertTrue(result.isValid(), "Should be valid: " + result.getError());
        assertNull(result.getMetadata()); // No metadata file in this JAR
    }

    @Test
    void verify_jarWithSPIAndMetadata_returnsOk() throws Exception {
        tempJar = createTestJar(true, true);
        PluginVerifier.VerificationResult result = verifier.verify(tempJar.getAbsolutePath());
        assertTrue(result.isValid(), "Should be valid: " + result.getError());
        assertNotNull(result.getMetadata());
        assertEquals("test-plugin", result.getMetadata().getName());
    }

    // ===== SHA-256 computation =====

    @Test
    void computeSha256_returnsHexDigest() throws Exception {
        tempJar = createTestJar(true, false);
        String sha256 = PluginVerifier.computeSha256(tempJar);
        assertNotNull(sha256);
        assertEquals(64, sha256.length()); // SHA-256 = 32 bytes = 64 hex chars
        assertTrue(sha256.matches("[0-9a-f]+"));
    }

    @Test
    void computeSha256_sameFileSameHash() throws Exception {
        tempJar = createTestJar(true, false);
        String hash1 = PluginVerifier.computeSha256(tempJar);
        String hash2 = PluginVerifier.computeSha256(tempJar);
        assertEquals(hash1, hash2);
    }

    // ===== Helper methods =====

    private File createTestJar(boolean includeSPI, boolean includeMetadata) throws Exception {
        File jarFile = File.createTempFile("test-plugin-", ".jar");
        Manifest manifest = new Manifest();
        JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile), manifest);

        if (includeSPI) {
            JarEntry spiEntry = new JarEntry(
                    "META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin");
            jos.putNextEntry(spiEntry);
            jos.write("com.example.TestPlugin\n".getBytes());
            jos.closeEntry();
        }

        if (includeMetadata) {
            JarEntry metaEntry = new JarEntry("META-INF/weaver-girl-plugin.properties");
            jos.putNextEntry(metaEntry);
            jos.write("plugin.name=test-plugin\nplugin.version=1.0.0\n".getBytes());
            jos.closeEntry();
        }

        jos.close();
        return jarFile;
    }
}
