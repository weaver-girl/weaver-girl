package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginJarScannerTest {

  @Test
  void scan_nonExistentDirectory_returnsEmptyList() {
    PluginJarScanner scanner = new PluginJarScanner();
    List<PluginClassLoader> result = scanner.scan("/nonexistent/path", getClass().getClassLoader());
    assertTrue(result.isEmpty());
  }

  @Test
  void scan_emptyDirectory_returnsEmptyList(@TempDir Path tempDir) {
    PluginJarScanner scanner = new PluginJarScanner();
    List<PluginClassLoader> result = scanner.scan(tempDir.toString(), getClass().getClassLoader());
    assertTrue(result.isEmpty());
  }

  @Test
  void scan_directoryWithNoJars_returnsEmptyList(@TempDir Path tempDir) throws Exception {
    // Create a non-JAR file
    new File(tempDir.toFile(), "readme.txt").createNewFile();

    PluginJarScanner scanner = new PluginJarScanner();
    List<PluginClassLoader> result = scanner.scan(tempDir.toString(), getClass().getClassLoader());
    assertTrue(result.isEmpty());
  }

  @Test
  void scan_filePathInsteadOfDirectory_returnsEmptyList(@TempDir Path tempDir) throws Exception {
    File textFile = new File(tempDir.toFile(), "somefile.txt");
    textFile.createNewFile();

    PluginJarScanner scanner = new PluginJarScanner();
    List<PluginClassLoader> result =
        scanner.scan(textFile.getAbsolutePath(), getClass().getClassLoader());
    assertTrue(result.isEmpty());
  }
}
