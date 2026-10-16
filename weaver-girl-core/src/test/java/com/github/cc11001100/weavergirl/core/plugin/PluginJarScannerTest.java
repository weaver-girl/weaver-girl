package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for PluginJarScanner.
 */
class PluginJarScannerTest {

  @Test
  void scan_nonexistentDirectory_returnsEmptyList() {
    PluginJarScanner scanner = new PluginJarScanner();
    List<PluginClassLoader> result = scanner.scan("/nonexistent/path/that/does/not/exist", getClass().getClassLoader());

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void scan_fileInsteadOfDirectory_returnsEmptyList(@TempDir Path tempDir) throws Exception {
    File file = tempDir.resolve("not-a-dir").toFile();
    file.createNewFile();

    PluginJarScanner scanner = new PluginJarScanner();
    List<PluginClassLoader> result = scanner.scan(file.getAbsolutePath(), getClass().getClassLoader());

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void scan_emptyDirectory_returnsEmptyList(@TempDir Path tempDir) {
    PluginJarScanner scanner = new PluginJarScanner();
    List<PluginClassLoader> result = scanner.scan(tempDir.toString(), getClass().getClassLoader());

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void scan_directoryWithNonJarFiles_returnsEmptyList(@TempDir Path tempDir) throws Exception {
    Files.writeString(tempDir.resolve("readme.txt"), "not a jar");
    Files.writeString(tempDir.resolve("config.xml"), "<xml/>");

    PluginJarScanner scanner = new PluginJarScanner();
    List<PluginClassLoader> result = scanner.scan(tempDir.toString(), getClass().getClassLoader());

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void pluginLibName_stripsSuffixAndVersion() {
    assertEquals("redis", PluginJarScanner.pluginLibName(new File("redis.jar")));
    assertEquals("redis", PluginJarScanner.pluginLibName(new File("redis-1.0.0-SNAPSHOT.jar")));
    assertEquals("my-plugin", PluginJarScanner.pluginLibName(new File("my-plugin-2.3.jar")));
  }

  @Test
  void scan_privateLib_isVisibleOnlyToItsPlugin(@TempDir Path tempDir) throws Exception {
    Files.write(tempDir.resolve("redis-1.0.jar"), new byte[] {0x50, 0x4b});
    Files.write(tempDir.resolve("jdbc.jar"), new byte[] {0x50, 0x4b});
    Files.createDirectories(tempDir.resolve("lib/redis"));
    Files.write(tempDir.resolve("lib/redis/jedis.jar"), new byte[] {0x50, 0x4b});
    Files.write(tempDir.resolve("lib/slf4j.jar"), new byte[] {0x50, 0x4b});
    // Same file name in both places: the plugin-private copy must win.
    Files.write(tempDir.resolve("lib/guava.jar"), new byte[] {0x50, 0x4b});
    Files.write(tempDir.resolve("lib/redis/guava.jar"), new byte[] {0x50, 0x4b});

    PluginJarScanner scanner = new PluginJarScanner();
    List<PluginJarScanner.PluginJar> jars =
        scanner.scanJars(tempDir.toString(), getClass().getClassLoader(), "", "");

    URL[] redis = urlsOf(jars, "redis-1.0.jar");
    URL[] jdbc = urlsOf(jars, "jdbc.jar");

    assertTrue(names(redis).containsAll(Arrays.asList("redis-1.0.jar", "jedis.jar", "slf4j.jar", "guava.jar")));
    assertFalse(names(jdbc).contains("jedis.jar"));
    assertTrue(names(jdbc).containsAll(Arrays.asList("jdbc.jar", "slf4j.jar", "guava.jar")));
    assertEquals(1, count(redis, "guava.jar"));
    assertTrue(pathOf(redis, "guava.jar").contains("/redis/"), pathOf(redis, "guava.jar"));
    assertFalse(pathOf(jdbc, "guava.jar").contains("/redis/"));
  }

  private static URL[] urlsOf(List<PluginJarScanner.PluginJar> jars, String name) {
    for (PluginJarScanner.PluginJar jar : jars) {
      if (jar.file.getName().equals(name)) {
        return ((URLClassLoader) jar.classLoader).getURLs();
      }
    }
    fail("no jar named " + name);
    return new URL[0];
  }

  private static List<String> names(URL[] urls) {
    List<String> names = new java.util.ArrayList<>();
    for (URL url : urls) {
      names.add(new File(url.getPath()).getName());
    }
    return names;
  }

  private static String pathOf(URL[] urls, String name) {
    for (URL url : urls) {
      if (new File(url.getPath()).getName().equals(name)) {
        return url.getPath();
      }
    }
    return "";
  }

  private static int count(URL[] urls, String name) {
    int count = 0;
    for (URL url : urls) {
      if (new File(url.getPath()).getName().equals(name)) {
        count++;
      }
    }
    return count;
  }

  @Test
  void constructor_createsInstance() {
    PluginJarScanner scanner = new PluginJarScanner();
    assertNotNull(scanner);
  }

  /**
   * Every jar is disabled, so the directory is not empty and yet nothing is loaded. The scan reports
   * that rather than an empty directory.
   */
  @Test
  void aDirectoryWhoseJarsAreAllDisabledLoadsNothing(@TempDir Path tempDir) throws Exception {
    java.nio.file.Files.write(tempDir.resolve("weaver-girl-plugin-jdbc-1.0.0.jar"), new byte[] {1});

    PluginJarScanner scanner = new PluginJarScanner();
    java.util.List<PluginJarScanner.PluginJar> loaded =
        scanner.scanJars(tempDir.toString(), getClass().getClassLoader(), "jdbc", null);

    assertTrue(loaded.isEmpty(), "a disabled jar was loaded");
  }

  /**
   * A file that is not a jar cannot be opened. Its metadata reads as nothing, and the scan still
   * returns it so the caller decides what to do with a jar that will not open.
   */
  @Test
  void aFileThatIsNotAJarYieldsNoMetadata(@TempDir Path tempDir) throws Exception {
    java.nio.file.Files.write(tempDir.resolve("broken.jar"), new byte[] {1, 2, 3});

    PluginJarScanner scanner = new PluginJarScanner();
    java.util.List<PluginJarScanner.PluginJar> loaded =
        scanner.scanJars(tempDir.toString(), getClass().getClassLoader(), null, null);

    assertEquals(1, loaded.size());
    assertNull(loaded.get(0).metadata, "a file that is not a jar produced metadata");
  }

  /**
   * {@code lib} is a directory this process cannot read, so listing it returns nothing. The plugin jar is
   * still loaded; the shared libraries are simply absent.
   */
  @Test
  void aLibDirectoryThatCannotBeReadIsSkipped(@TempDir Path tempDir) throws Exception {
    java.nio.file.Files.write(tempDir.resolve("plain.jar"), new byte[] {1, 2, 3});
    Path lib = tempDir.resolve("lib");
    java.nio.file.Files.createDirectory(lib);
    lib.toFile().setReadable(false);

    PluginJarScanner scanner = new PluginJarScanner();
    try {
      java.util.List<PluginJarScanner.PluginJar> loaded =
          scanner.scanJars(tempDir.toString(), getClass().getClassLoader(), null, null);

      assertEquals(1, loaded.size(), "an unreadable lib directory dropped the plugin");
    } finally {
      lib.toFile().setReadable(true);
    }
  }
}
