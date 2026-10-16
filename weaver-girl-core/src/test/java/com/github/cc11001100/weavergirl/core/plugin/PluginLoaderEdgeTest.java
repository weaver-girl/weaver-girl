package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The edges {@link PluginLoader} only reaches with plugins that declare something unusual: a hard
 * requirement named twice, a dependency on a plugin that exports nothing, libraries named only as
 * blanks, and a plugin whose {@code destroy()} throws while it is being unloaded.
 */
class PluginLoaderEdgeTest {

  @TempDir Path tempDir;

  @Test
  void duplicateRequirementAndADependencyWithoutExportsStillLoads() throws Exception {
    // Three plugins is what pushes the loader onto the parallel path, where a dependency level is
    // computed. The blank entry has to survive into that computation.
    pluginJar("base.jar", "fixture.BasePlugin", "base", "", "");
    pluginJar(
        "needy.jar",
        "fixture.NeedyPlugin",
        "needy",
        "plugin.requires=base,base\n",
        "  public String[] requires() { return new String[]{\"base\", \"base\"}; }\n"
            + "  public String[] depends() { return new String[]{\"base\", null}; }\n");
    pluginJar(
        "follower.jar",
        "fixture.FollowerPlugin",
        "follower",
        "",
        "  public String[] depends() { return new String[]{\"base\"}; }\n");
    pluginJar("extra.jar", "fixture.ExtraPlugin", "extra", "", "");

    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());
    assertEquals(4, loaded.size());

    WeaverPlugin needy = named(loaded, "needy");
    // Named by both the method and the metadata, so it is remembered twice.
    assertEquals(java.util.Arrays.asList("base", "base"), loader.requirementsOf(needy));

    // Unloading the requirement unloads the plugin that names it, and its destroy() throws on the way
    // out. The loader logs that and carries on to the plugin that only depends on it.
    for (File jar : tempDir.toFile().listFiles()) {
      if (jar.getName().equals("base.jar")) {
        assertTrue(jar.delete());
      }
    }
    List<WeaverPlugin> removed = loader.unloadMissingJars(tempDir.toString());
    assertTrue(names(removed).contains("base"));
    assertTrue(names(removed).contains("needy"));
  }

  @Test
  void aPluginThatNamesOnlyBlankLibrariesStaysActive() throws Exception {
    pluginJar(
        "blank.jar",
        "fixture.BlankPlugin",
        "blank",
        "",
        "  public String[] instrumentedLibraries() { return new String[]{null, \"  \"}; }\n");
    pluginJar(
        "absent.jar",
        "fixture.AbsentPlugin",
        "absent",
        "",
        "  public String[] instrumentedLibraries() { return new String[]{\"com.example.Missing\"}; }\n");
    pluginJar(
        "present.jar",
        "fixture.PresentPlugin",
        "present",
        "",
        "  public String[] instrumentedLibraries() {"
            + " return new String[]{\"java.lang.String\"}; }\n");

    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    // Blanks declare nothing, so the plugin loads. A library that is genuinely absent leaves its
    // plugin out, and one that is present lets it through.
    assertTrue(names(loaded).contains("blank"));
    assertTrue(names(loaded).contains("present"));
    assertTrue(!names(loaded).contains("absent"));
  }

  @Test
  void aPluginThatDisablesItselfIsInactiveRatherThanFailed() throws Exception {
    pluginJar("one.jar", "fixture.OnePlugin", "one", "", "");
    pluginJar(
        "switched.jar",
        "fixture.SwitchedPlugin",
        "switched",
        "",
        "  public boolean isEnabled(com.github.cc11001100.weavergirl.api.plugin.PluginContext c)"
            + " { return false; }\n");
    pluginJar(
        "broken.jar",
        "fixture.BrokenPlugin",
        "broken",
        "",
        "  public void init(com.github.cc11001100.weavergirl.api.plugin.PluginContext c)"
            + " { throw new IllegalStateException(\"cannot start\"); }\n");
    // A dependency that names a plugin this batch does not contain. It must be skipped while the
    // dependency levels are computed, not abort the whole load.
    pluginJar(
        "waiting.jar",
        "fixture.WaitingPlugin",
        "waiting",
        "",
        "  public String[] depends() { return new String[]{\"not-here\"}; }\n");

    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertTrue(names(loaded).contains("one"));
    assertTrue(names(loaded).contains("waiting"));
    assertTrue(!names(loaded).contains("switched"));
    assertTrue(!names(loaded).contains("broken"));
  }

  /**
   * A plugin that names several libraries is inactive when none of them is present, and every name is
   * joined into the reason it records. A plugin that returns null from {@code depends()} declares nothing
   * and still loads.
   */
  @Test
  void severalMissingLibrariesAreNamedTogether() throws Exception {
    pluginJar(
        "needy.jar",
        "fixture.NeedyPlugin",
        "needy",
        "",
        "  public String[] instrumentedLibraries() { return new String[]{\"com.example.Missing\", \"com.example.AlsoMissing\"}; }\n");
    pluginJar(
        "loose.jar",
        "fixture.LoosePlugin",
        "loose",
        "",
        "  public String[] depends() { return null; }\n");

    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertTrue(names(loaded).contains("loose"), "a null dependency list stopped the plugin loading");
    assertTrue(!names(loaded).contains("needy"), "a plugin with nothing to instrument stayed active");
  }

  /**
   * A provider whose constructor throws is reported and skipped. The jar next to it still loads, so one
   * broken plugin does not take the directory down with it.
   */
  @Test
  void aProviderThatFailsToInstantiateIsSkipped() throws Exception {
    pluginJar(
        "broken.jar",
        "fixture.BrokenPlugin",
        "broken",
        "",
        "  public BrokenPlugin() { throw new RuntimeException(\"cannot start\"); }\n");
    pluginJar("fine.jar", "fixture.FinePlugin", "fine", "", "");

    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertEquals(1, loaded.size(), "the jar beside the broken one was not loaded");
    assertEquals("fine", loaded.get(0).name());
  }

  @Test
  void destroyThatThrowsWhileUnloadingIsLoggedAndNotFatal() throws Exception {
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

    // The loader no longer holds it, so the unload takes the branch that destroys the plugin directly.
    loader.destroyAll();
    assertTrue(loader.unloadDependentsOf(Collections.singletonList(loaded.get(0))).isEmpty());
  }

  private static WeaverPlugin named(List<WeaverPlugin> plugins, String name) {
    for (WeaverPlugin plugin : plugins) {
      if (name.equals(plugin.name())) {
        return plugin;
      }
    }
    throw new AssertionError(name + " was not loaded: " + names(plugins));
  }

  private static List<String> names(List<WeaverPlugin> plugins) {
    List<String> names = new ArrayList<>();
    for (WeaverPlugin plugin : plugins) {
      names.add(plugin.name());
    }
    return names;
  }

  /** One compiled plugin class plus its SPI entry and metadata, packed into {@code tempDir}. */
  private Path pluginJar(
      String fileName, String className, String pluginName, String metadata, String extraMembers)
      throws Exception {
    String simple = className.substring(className.lastIndexOf('.') + 1);
    String source =
        "package fixture;\n"
            + "public class "
            + simple
            + " implements com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin {\n"
            + "  public String name() { return \""
            + pluginName
            + "\"; }\n"
            + (extraMembers.contains(" void init(")
                ? ""
                : "  public void init(com.github.cc11001100.weavergirl.api.plugin.PluginContext c) {}\n")
            + extraMembers
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
        put(
            out,
            "META-INF/weaver-girl-plugin.properties",
            ("plugin.name=" + pluginName + "\n" + metadata).getBytes(StandardCharsets.UTF_8));
      }
      return jar;
    } finally {
      delete(src.toFile());
      delete(classes.toFile());
    }
  }

  private static void put(java.util.jar.JarOutputStream out, String name, Path file) throws Exception {
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
