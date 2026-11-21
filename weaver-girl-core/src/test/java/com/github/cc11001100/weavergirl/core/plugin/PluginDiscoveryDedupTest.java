package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin class must be discovered once, not once per jar.
 *
 * <p>Each capability jar gets its own child-first {@link PluginClassLoader}, and that loader also
 * answers the service file of its parent — the application class path. Without a filter the plugin
 * an application ships was reported once per capability jar: 141 instances for a one-plugin
 * application, 141 copies of its interceptors, and 141 destroy calls at shutdown. Both filters are
 * exercised here against loader shapes that reproduce the failure without needing a real jar.
 */
class PluginDiscoveryDedupTest {

  @TempDir Path tempDir;

  /** Two loaders that both answer the parent's service file must not both yield the same class. */
  @Test
  void aClassSeenThroughItsOwnLoaderIsNotDiscoveredThroughTheParentToo() throws Exception {
    Path classes = compilePlugin("fixture.DedupPlugin", "dedup-plugin");

    // The "application class path": a plain URL loader over the classes directory.
    try (URLClassLoader parent =
        new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
      // Two per-jar loaders whose parent is the application loader, each empty on its own.
      URLClassLoader jarA = new URLClassLoader(new URL[0], parent);
      URLClassLoader jarB = new URLClassLoader(new URL[0], parent);

      PluginLoader loader = new PluginLoader();
      List<WeaverPlugin> seenThroughA = loader.discover(jarA);
      List<WeaverPlugin> seenThroughB = loader.discover(jarB);

      // Both loaders can SEE the parent's provider — that is why a naive scan duplicates it.
      assertEquals(1, seenThroughA.size());
      assertEquals(1, seenThroughB.size());

      // The per-jar filter the directory path applies keeps only what that jar defined.
      assertTrue(
          definedBy(seenThroughA, jarA).isEmpty(),
          "a plugin the jar did not define must not be attributed to it");
      assertTrue(
          definedBy(seenThroughB, jarB).isEmpty(),
          "a plugin the jar did not define must not be attributed to it");
      // The parent's own loader does attribute it, and reports it once.
      assertEquals(1, loader.discover(parent).size());
    }
  }

  @Test
  void theSameClassFromOneLoaderIsReportedOnce() throws Exception {
    Path classes = compilePlugin("fixture.SinglePlugin", "single-plugin");
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
      // ServiceLoader can yield a provider more than once when several service files name it.
      List<WeaverPlugin> discovered = new PluginLoader().discover(loader);
      assertEquals(1, discovered.size());
      assertEquals("single-plugin", discovered.get(0).name());
    }
  }

  private static List<WeaverPlugin> definedBy(List<WeaverPlugin> plugins, ClassLoader loader) {
    List<WeaverPlugin> owned = new java.util.ArrayList<>();
    for (WeaverPlugin plugin : plugins) {
      if (plugin.getClass().getClassLoader() == loader) {
        owned.add(plugin);
      }
    }
    return owned;
  }

  /** One plugin class plus its SPI entry, compiled into a directory under {@code tempDir}. */
  private Path compilePlugin(String className, String pluginName) throws Exception {
    Path src = Files.createTempDirectory("dedup-src");
    Path classes = tempDir.resolve(className.replace('.', '_'));
    String simple = className.substring(className.lastIndexOf('.') + 1);
    String source =
        "package fixture;\n"
            + "public class "
            + simple
            + " implements com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin {\n"
            + "  public String name() { return \""
            + pluginName
            + "\"; }\n"
            + "  public void init(com.github.cc11001100.weavergirl.api.plugin.PluginContext c) {}\n"
            + "  public void registerInterceptors("
            + "com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry r) {}\n"
            + "}\n";
    Path javaFile = src.resolve(simple + ".java");
    Files.write(javaFile, source.getBytes(StandardCharsets.UTF_8));
    javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
    javax.tools.StandardJavaFileManager files =
        compiler.getStandardFileManager(null, null, null);
    Files.createDirectories(classes);
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
    Path services = classes.resolve("META-INF/services");
    Files.createDirectories(services);
    Files.write(
        services.resolve(WeaverPlugin.class.getName()),
        (className + "\n").getBytes(StandardCharsets.UTF_8));
    // Keep the directory around for the duration of the assertion, not the temp source.
    new File(src.toString()).deleteOnExit();
    return classes;
  }
}
