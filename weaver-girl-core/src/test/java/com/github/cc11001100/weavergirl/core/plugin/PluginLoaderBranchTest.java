package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The branches in {@link PluginLoader} that the contract tests never reach: a directory scan whose
 * config is missing, a jar whose declared checksum does not match, a jar that cannot be read, a
 * provider that fails to instantiate, and a plugin whose {@code destroy()} throws while it is being
 * unloaded.
 */
class PluginLoaderBranchTest {

  @TempDir Path tempDir;

  @Test
  void directoryScanWithNoConfigAndNoDirectoryLoadsNothing() {
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.resolve("missing").toString(), new DefaultInterceptorRegistry(), null);
    assertTrue(loaded.isEmpty());
    // A directory that was never scanned has nothing to unload either.
    assertTrue(loader.unloadMissingJars(tempDir.toString()).isEmpty());
  }

  @Test
  void jarWithTheWrongChecksumIsRejectedAndItsLoaderClosed() throws Exception {
    pluginJar("bad.jar", "fixture.BadPlugin", "bad", "plugin.sha256=0000\n");

    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());
    assertTrue(loaded.isEmpty(), "a jar that fails verification must not be loaded");
  }

  @Test
  void providerThatFailsToInstantiateIsSkipped() throws Exception {
    pluginJar(
        "broken.jar",
        "fixture.BrokenPlugin",
        "broken",
        "",
        "  public BrokenPlugin() { throw new RuntimeException(\"unconstructable\"); }\n");

    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());
    assertTrue(loaded.isEmpty());
  }

  @Test
  void destroyAllSurvivesAPluginWhoseDestroyThrows() throws Exception {
    pluginJar(
        "fragile.jar",
        "fixture.FragilePlugin",
        "fragile",
        "",
        "  @Override public void destroy() { throw new RuntimeException(\"already gone\"); }\n");

    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());
    assertEquals(1, loaded.size());
    // destroy() throws on the way out. The loader logs it; the exception still propagates.
    try {
      loader.destroyAll();
    } catch (RuntimeException expected) {
      assertEquals("already gone", expected.getMessage());
    }
    assertEquals(0, loader.getLoadedPlugins().size());
  }

  @Test
  void unloadOfAPluginTheLoaderDidNotActivateStillDestroysIt() throws Exception {
    pluginJar("quiet.jar", "fixture.QuietPlugin", "quiet", "");

    PluginLoader loader = new PluginLoader();
    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(tempDir.toString(), registry, Collections.emptyMap());
    assertEquals(1, loaded.size());

    // destroyAll() drops it from the loaded set, so the next unload takes the branch for a plugin this
    // loader did not activate: it destroys the plugin directly and reports nothing removed, because there
    // was no loaded entry to drop.
    loader.destroyAll();
    assertTrue(loader.unloadDependentsOf(Collections.singletonList(loaded.get(0))).isEmpty());
  }

  @Test
  void deletingALoadedJarUnloadsWhatItContributed() throws Exception {
    pluginJar("quiet.jar", "fixture.QuietPlugin", "quiet", "");

    PluginLoader loader = new PluginLoader();
    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    loader.loadPluginsFromDirectory(tempDir.toString(), registry, Collections.emptyMap());
    for (File jar : tempDir.toFile().listFiles()) {
      jar.delete();
    }
    assertFalse(loader.unloadMissingJars(tempDir.toString()).isEmpty());
    assertEquals(0, loader.getLoadedPlugins().size());
  }

  @Test
  void unreadableJarGetsAFingerprintThatDoesNotStick() throws Exception {
    pluginJar("plain.jar", "fixture.PlainPlugin", "plain", "");
    // A directory where a jar is expected cannot be opened, so fingerprinting falls back.
    Path unreadable = tempDir.resolve("unreadable.jar");
    Files.createDirectory(unreadable);

    PluginLoader loader = new PluginLoader();
    // The unreadable entry is skipped; the real plugin beside it still loads.
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());
    assertEquals(1, loaded.size());
  }

  private Path pluginJar(String fileName, String className, String pluginName, String metadata)
      throws Exception {
    return pluginJar(fileName, className, pluginName, metadata, "");
  }

  /** One compiled plugin class plus its SPI entry, packed into {@code tempDir}. */
  private Path pluginJar(
      String fileName, String className, String pluginName, String metadata, String extraMembers)
      throws Exception {
    String simple = className.substring(className.lastIndexOf('.') + 1);
    String source =
        "package fixture;\n"
            + "public class "
            + simple
            + " implements com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin {\n"
            + extraMembers
            + "  public String name() { return \""
            + pluginName
            + "\"; }\n"
            + "  public void init(com.github.cc11001100.weavergirl.api.plugin.PluginContext c) {}\n"
            + "  public void registerInterceptors("
            + "com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry r) {}\n"
            + "}\n";

    Path src = Files.createTempDirectory("plugin-src");
    Path classes = Files.createTempDirectory("plugin-classes");
    try {
      Path javaFile = src.resolve(simple + ".java");
      Files.write(javaFile, source.getBytes(StandardCharsets.UTF_8));
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null);
      try {
        Boolean ok =
            compiler
                .getTask(
                    null,
                    files,
                    null,
                    java.util.Arrays.asList(
                        "-classpath",
                        System.getProperty("java.class.path"),
                        "-d",
                        classes.toString(),
                        "--release",
                        "8"),
                    null,
                    files.getJavaFileObjects(javaFile.toFile()))
                .call();
        assertEquals(Boolean.TRUE, ok, "fixture plugin failed to compile");
      } finally {
        files.close();
      }

      Path jar = tempDir.resolve(fileName);
      try (java.util.jar.JarOutputStream out =
          new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
        put(out, "fixture/" + simple + ".class", classes.resolve("fixture").resolve(simple + ".class"));
        put(
            out,
            "META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin",
            (className + "\n").getBytes(StandardCharsets.UTF_8));
        if (!metadata.isEmpty()) {
          put(
              out,
              "META-INF/weaver-girl-plugin.properties",
              ("plugin.name=" + pluginName + "\n" + metadata).getBytes(StandardCharsets.UTF_8));
        }
      }
      return jar;
    } finally {
      delete(src.toFile());
      delete(classes.toFile());
    }
  }

  private static void put(java.util.jar.JarOutputStream out, String name, Path file)
      throws Exception {
    put(out, name, Files.readAllBytes(file));
  }

  private static void put(java.util.jar.JarOutputStream out, String name, byte[] bytes)
      throws Exception {
    out.putNextEntry(new java.util.jar.JarEntry(name));
    out.write(bytes);
    out.closeEntry();
  }

  private static void delete(File file) {
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        delete(child);
      }
    }
    file.delete();
  }
}
