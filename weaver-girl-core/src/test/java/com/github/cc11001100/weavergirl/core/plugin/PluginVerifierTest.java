package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.core.plugin.PluginVerifier.VerificationResult;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A jar that declares a checksum is refused when its bytes do not match. */
class PluginVerifierTest {

  private static final String SPI =
      "META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin";

  @TempDir java.nio.file.Path tempDir;

  @Test
  void matchingChecksumPasses() throws Exception {
    // The digest ignores the metadata entry, so any placeholder yields the same content hash.
    File placeholder = jar("placeholder");
    String hash;
    JarFile open = new JarFile(placeholder);
    try {
      hash = PluginVerifier.computeContentSha256(open);
    } finally {
      open.close();
    }

    VerificationResult result = new PluginVerifier().verify(jar(hash).getAbsolutePath());

    assertTrue(result.isValid(), result.getError());
  }

  @Test
  void wrongChecksumIsRejected() throws Exception {
    File jar = jar("0000000000000000000000000000000000000000000000000000000000000000");

    VerificationResult result = new PluginVerifier().verify(jar.getAbsolutePath());

    assertFalse(result.isValid());
    assertTrue(result.getError().contains("SHA-256 mismatch"));
  }

  @Test
  void noDeclaredChecksumPasses() throws Exception {
    File jar = jar(null);

    assertTrue(new PluginVerifier().verify(jar.getAbsolutePath()).isValid());
  }

  @Test
  void missingSpiIsRejected() throws Exception {
    File jar = tempDir.resolve("empty.jar").toFile();
    JarOutputStream out = new JarOutputStream(new FileOutputStream(jar));
    out.close();

    assertFalse(new PluginVerifier().verify(jar.getAbsolutePath()).isValid());
  }

  /** A path that names nothing, and one that names a directory, are both reported as missing. */
  @Test
  void aMissingOrNonFilePathIsRejected() {
    VerificationResult missing =
        new PluginVerifier().verify(tempDir.resolve("absent.jar").toString());
    assertFalse(missing.isValid());
    assertTrue(missing.getError().contains("not found"));

    VerificationResult directory = new PluginVerifier().verify(tempDir.toString());
    assertFalse(directory.isValid());
    assertTrue(directory.getError().contains("not found"));
  }

  /** A file that is not a jar cannot be read, and that is reported rather than thrown. */
  @Test
  void aFileThatIsNotAJarIsRejected() throws Exception {
    File garbage = tempDir.resolve("garbage.jar").toFile();
    java.nio.file.Files.write(garbage.toPath(), new byte[] {1, 2, 3, 4});

    VerificationResult result = new PluginVerifier().verify(garbage.getAbsolutePath());

    assertFalse(result.isValid());
    assertTrue(result.getError().contains("Failed to read JAR"), result.getError());
  }

  /** A directory entry is skipped by the digest, and an entry larger than the buffer is hashed whole. */
  @Test
  void aDirectoryEntryAndALargeEntryStillHash() throws Exception {
    File jar = tempDir.resolve("large.jar").toFile();
    JarOutputStream out = new JarOutputStream(new FileOutputStream(jar));
    try {
      out.putNextEntry(new JarEntry("com/"));
      out.closeEntry();
      put(out, SPI, new String(new char[20_000]).replace('\0', 'x'));
    } finally {
      out.close();
    }

    JarFile open = new JarFile(jar);
    try {
      String hash = PluginVerifier.computeContentSha256(open);
      assertTrue(hash.matches("[0-9a-f]{64}"), hash);
    } finally {
      open.close();
    }
    assertTrue(PluginVerifier.computeSha256(jar).matches("[0-9a-f]{64}"));
  }

  /** Hashing a file that cannot be read fails loudly rather than returning a partial digest. */
  @Test
  void hashingAnUnreadableFileFails() {
    RuntimeException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            RuntimeException.class,
            () -> PluginVerifier.computeSha256(tempDir.resolve("absent.jar").toFile()));
    assertTrue(ex.getMessage().contains("SHA-256"), ex.getMessage());
  }

  /** A jar with the SPI entry and, when {@code sha256} is set, a metadata file declaring it. */
  private File jar(String sha256) throws Exception {
    File jar = tempDir.resolve("plugin-" + System.nanoTime() + ".jar").toFile();
    JarOutputStream out = new JarOutputStream(new FileOutputStream(jar));
    try {
      put(out, SPI, "com.example.Plugin\n");
      if (sha256 != null) {
        put(out, "META-INF/weaver-girl-plugin.properties", "plugin.name=demo\nplugin.sha256=" + sha256 + "\n");
      }
    } finally {
      out.close();
    }
    return jar;
  }

  private static void put(JarOutputStream out, String name, String content) throws Exception {
    out.putNextEntry(new JarEntry(name));
    out.write(content.getBytes(StandardCharsets.UTF_8));
    out.closeEntry();
  }
}
