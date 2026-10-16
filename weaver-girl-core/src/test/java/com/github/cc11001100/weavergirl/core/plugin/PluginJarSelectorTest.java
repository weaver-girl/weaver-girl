package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A capability that is switched off must not have its jar selected, so it is never loaded. */
class PluginJarSelectorTest {

  @TempDir Path tempDir;

  @Test
  void capabilityNameStripsPrefixAndVersion() {
    assertEquals("jdbc", PluginJarSelector.capabilityOf("weaver-girl-plugin-jdbc-1.0.0.jar"));
    assertEquals(
        "jdbc", PluginJarSelector.capabilityOf("weaver-girl-plugin-jdbc-1.0.0-SNAPSHOT.jar"));
    assertEquals(
        "httpclient",
        PluginJarSelector.capabilityOf("weaver-girl-plugin-httpclient-2.3.1.jar"));
    // Not one of our jars: no derived capability, so it is always loaded.
    assertNull(PluginJarSelector.capabilityOf("my-custom-plugin-1.0.jar"));
    assertNull(PluginJarSelector.capabilityOf("weaver-girl-plugin-jdbc.jar"));
    // The agent's own optional capabilities use the runtime prefix.
    assertEquals(
        "prometheus", PluginJarSelector.capabilityOf("weaver-girl-runtime-prometheus-1.0.0.jar"));
    assertEquals(
        "config-watch",
        PluginJarSelector.capabilityOf("weaver-girl-runtime-config-watch-1.0.0-SNAPSHOT.jar"));
  }

  @Test
  void disabledCapabilityIsExcluded() throws Exception {
    File jdbc = jar("weaver-girl-plugin-jdbc-1.0.0.jar", "jdbc");
    File servlet = jar("weaver-girl-plugin-servlet-1.0.0.jar", "servlet");

    List<File> selected =
        PluginJarSelector.select(Arrays.asList(jdbc, servlet), "jdbc", null);

    assertEquals(Arrays.asList(servlet), selected);
  }

  @Test
  void markerNameWinsOverJarName() throws Exception {
    // The exception capability's plugin calls itself "exception-monitor", not "exception".
    File exception =
        jar("weaver-girl-plugin-exception-1.0.0.jar", "exception-monitor");

    List<File> selected =
        PluginJarSelector.select(Arrays.asList(exception), "exception-monitor", null);

    assertTrue(selected.isEmpty(), "disabling the plugin name must drop its jar");
  }

  @Test
  void jarWithSeveralPluginsStaysWhenAnyIsEnabled() throws Exception {
    File async =
        jar(
            "weaver-girl-plugin-async-1.0.0.jar",
            "async-context-propagation",
            "completable-future-context-propagation");

    List<File> disabled =
        PluginJarSelector.select(Arrays.asList(async), "async-context-propagation", null);
    assertEquals(Arrays.asList(async), disabled, "one plugin still enabled, jar must load");

    List<File> bothDisabled =
        PluginJarSelector.select(
            Arrays.asList(async),
            "async-context-propagation,completable-future-context-propagation",
            null);
    assertTrue(bothDisabled.isEmpty(), "every plugin disabled, jar must not load");
  }

  @Test
  void enabledPluginsIsAnAllowlist() throws Exception {
    File jdbc = jar("weaver-girl-plugin-jdbc-1.0.0.jar", "jdbc");
    File kafka = jar("weaver-girl-plugin-kafka-1.0.0.jar", "kafka");
    File thirdParty = jar("acme-tracer-1.2.jar");

    List<File> selected =
        PluginJarSelector.select(Arrays.asList(jdbc, kafka, thirdParty), "jdbc", "kafka");

    assertEquals(Arrays.asList(kafka, thirdParty), selected);
  }

  @Test
  void nothingConfiguredLoadsEverything() throws Exception {
    File jdbc = jar("weaver-girl-plugin-jdbc-1.0.0.jar", "jdbc");
    File servlet = jar("weaver-girl-plugin-servlet-1.0.0.jar", "servlet");

    List<File> selected = PluginJarSelector.select(Arrays.asList(jdbc, servlet), null, "");

    assertEquals(Arrays.asList(jdbc, servlet), selected);
  }

  @Test
  void scannerNeverOpensADisabledJar(@TempDir java.nio.file.Path dir) throws Exception {
    jar(dir, "weaver-girl-plugin-jdbc-1.0.0.jar", "jdbc");
    jar(dir, "weaver-girl-plugin-servlet-1.0.0.jar", "servlet");

    PluginJarScanner scanner = new PluginJarScanner();
    List<PluginClassLoader> loaders =
        scanner.scan(dir.toString(), getClass().getClassLoader(), "servlet", null);

    assertEquals(1, loaders.size());
    assertNotNull(loaders.get(0).loadClass(Probe.class.getName()));
  }

  /** A class the test can ask a plugin loader for, proving the loader was built at all. */
  public static class Probe {}

  private File jar(String name, String... capabilities) throws Exception {
    return jar(tempDir, name, capabilities);
  }

  private static File jar(java.nio.file.Path dir, String name, String... capabilities)
      throws Exception {
    File file = dir.resolve(name).toFile();
    JarOutputStream out = new JarOutputStream(new FileOutputStream(file));
    try {
      if (capabilities.length > 0) {
        out.putNextEntry(new JarEntry(PluginJarSelector.CAPABILITY_MARKER));
        StringBuilder body = new StringBuilder();
        for (String capability : capabilities) {
          body.append(capability).append('\n');
        }
        out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
      // One real class so a PluginClassLoader over this jar can resolve something.
      out.putNextEntry(
          new JarEntry(Probe.class.getName().replace('.', '/') + ".class"));
      out.write(classBytes(Probe.class));
      out.closeEntry();
    } finally {
      out.close();
    }
    return file;
  }

  private static byte[] classBytes(Class<?> cls) throws Exception {
    String resource = cls.getName().replace('.', '/') + ".class";
    java.io.InputStream in = cls.getClassLoader().getResourceAsStream(resource);
    try {
      java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
      byte[] chunk = new byte[4096];
      int read;
      while ((read = in.read(chunk)) != -1) {
        buf.write(chunk, 0, read);
      }
      return buf.toByteArray();
    } finally {
      in.close();
    }
  }

  @Test
  void parseTrimsAndDropsBlanks() {
    Set<String> parsed = PluginJarSelector.parse(" jdbc, ,kafka ,");
    assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("jdbc", "kafka")), parsed);
    assertTrue(PluginJarSelector.parse(null).isEmpty());
    assertTrue(PluginJarSelector.parse("   ").isEmpty());
  }

  /** Nothing to choose from selects nothing. */
  @Test
  void nothingToChooseFromSelectsNothing() {
    assertTrue(PluginJarSelector.select(null, null, null).isEmpty());
    assertTrue(PluginJarSelector.select(Collections.emptyList(), null, null).isEmpty());
  }

  /** A name that is not a jar, and a jar whose name carries no version, name no capability. */
  @Test
  void namesThatFollowNoConventionNameNoCapability() {
    assertNull(PluginJarSelector.capabilityOf(null));
    assertNull(PluginJarSelector.capabilityOf("weaver-girl-plugin-jdbc.zip"));
    assertNull(PluginJarSelector.capabilityOf("weaver-girl-plugin-jdbc.jar"));
  }

  /** A jar that cannot be read as a jar contributes no marker, so its name decides. */
  @Test
  void aJarThatCannotBeReadFallsBackToItsName() throws Exception {
    File notAJar = File.createTempFile("weaver-girl-plugin-redis-1.0.0", ".jar");
    notAJar.deleteOnExit();
    java.nio.file.Files.write(notAJar.toPath(), new byte[] {1, 2, 3});

    assertEquals(Collections.singleton("redis"), PluginJarSelector.capabilitiesOf(notAJar));
    assertTrue(PluginJarSelector.capabilitiesOf(new File("/no/such/file.jar")).isEmpty());
  }
}
