package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.testsupport.Statics;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The branches of {@link PluginLoader} a directory of jars does not reach. */
class PluginLoaderCoverageTest {

  @TempDir Path tempDir;

  /** A plugin whose initialization reports nothing because the event bus is down. */
  @Test
  void aLifecycleEventThatCannotBePublishedDoesNotFailTheLoad() throws Exception {
    Field current = com.github.cc11001100.weavergirl.api.tracing.Tracer.class.getDeclaredField("CURRENT_SPAN");
    Object real = Statics.read(current, null);
    Statics.write(current, null, threadLocalThatFails());
    try {
      pluginJar("quiet.jar", "fixture.QuietPlugin", "quiet", true, null, null);
      PluginLoader loader = new PluginLoader();
      List<WeaverPlugin> loaded =
          loader.loadPluginsFromDirectory(
              tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

      assertEquals(1, loaded.size(), "a lifecycle event failing aborted the load");
    } finally {
      Statics.write(current, null, real);
    }
  }

  /** A plugin that switches itself off still has its silence reported, even when reporting fails. */
  @Test
  void anInactivePluginWhoseEventFailsIsStillInactive() throws Exception {
    Field current = com.github.cc11001100.weavergirl.api.tracing.Tracer.class.getDeclaredField("CURRENT_SPAN");
    Object real = Statics.read(current, null);
    Statics.write(current, null, threadLocalThatFails());
    try {
      // Three plugins, so they load in parallel — the path that reports a plugin switching itself off.
      pluginJar("asleep.jar", "fixture.AsleepPlugin", "asleep", false, null, null);
      pluginJar("dozing.jar", "fixture.DozingPlugin", "dozing", false, null, null);
      pluginJar("resting.jar", "fixture.RestingPlugin", "resting", false, null, null);
      PluginLoader loader = new PluginLoader();
      List<WeaverPlugin> loaded =
          loader.loadPluginsFromDirectory(
              tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

      assertTrue(loaded.isEmpty(), "a plugin that disabled itself was loaded");
    } finally {
      Statics.write(current, null, real);
    }
  }

  /**
   * Three plugins are enough to load in parallel, and a dependency that skips a level leaves that level
   * empty. The event bus is down, so each plugin's lifecycle event fails to publish and is swallowed.
   */
  @Test
  void pluginsThatShareNoDependencyLoadInParallel() throws Exception {
    Field current = com.github.cc11001100.weavergirl.api.tracing.Tracer.class.getDeclaredField("CURRENT_SPAN");
    Object real = Statics.read(current, null);
    Statics.write(current, null, threadLocalThatFails());
    try {
      pluginJar("base.jar", "fixture.BasePlugin", "base", true, null, null);
      pluginJar("middle.jar", "fixture.MiddlePlugin", "middle", true, "base", null);
      // Depends on the root rather than the plugin before it, so the level in between is empty.
      pluginJar("top.jar", "fixture.TopPlugin", "top", true, "base", null);
      pluginJar("side.jar", "fixture.SidePlugin", "side", true, "base", null);
      PluginLoader loader = new PluginLoader();
      List<WeaverPlugin> loaded =
          loader.loadPluginsFromDirectory(
              tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

      assertEquals(4, loaded.size(), "a plugin was dropped by the parallel load");
    } finally {
      Statics.write(current, null, real);
    }
  }

  /**
   * A plugin that depends on one which never declared any metadata has nothing to wire: the dependency is
   * loaded, but it exported no package, so the export pass skips it and both plugins still load.
   */
  @Test
  void aDependencyThatDeclaredNoMetadataExportsNothing() throws Exception {
    // "plain" carries no metadata file at all, so there is no export list to read from it.
    pluginJar("plain.jar", "fixture.PlainPlugin", "plain", true, null, null, false);
    pluginJar("needy.jar", "fixture.NeedyPlugin", "needy", true, "plain", null, true);
    PluginLoader loader = new PluginLoader();

    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertEquals(2, loaded.size(), "a dependency without metadata dropped the plugin that needs it");
  }

  /**
   * A dependency on a plugin that is not in this batch has no level to wait for, and a plugin that
   * exports a package to a dependency which exported nothing finds no exporter to wire.
   */
  @Test
  void aMissingDependencyAndAnExportWithoutAProviderAreBothSkipped() throws Exception {
    pluginJar("root.jar", "fixture.RootPlugin", "root", true, "nobody", "fixture.missing");
    pluginJar("one.jar", "fixture.OnePlugin", "one", true, "root", null);
    pluginJar("two.jar", "fixture.TwoPlugin", "two", true, "root", null);
    PluginLoader loader = new PluginLoader();

    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertEquals(3, loaded.size(), "a soft dependency on an absent plugin dropped the depender");
  }

  /**
   * Unloading a plugin also unloads the one that requires it, and then the one that requires that. The plugin
   * being removed is walked again once its name is in the unloaded set, and that pass skips it.
   */
  @Test
  void unloadingAPluginUnloadsTheChainThatRequiresIt() throws Exception {
    WeaverPlugin base = plugin("base", false);
    WeaverPlugin middle = plugin("middle", false);
    WeaverPlugin top = plugin("top", true);
    PluginLoader loader = new PluginLoader();
    java.lang.reflect.Field loaded = PluginLoader.class.getDeclaredField("loadedPlugins");
    loaded.setAccessible(true);
    ((java.util.List<WeaverPlugin>) loaded.get(loader)).addAll(java.util.Arrays.asList(base, middle, top));
    java.lang.reflect.Field requirements = PluginLoader.class.getDeclaredField("requirements");
    java.util.Map<WeaverPlugin, java.util.List<String>> required =
        (java.util.Map<WeaverPlugin, java.util.List<String>>) Statics.read(requirements, loader);
    required.put(middle, java.util.Collections.singletonList("base"));
    required.put(top, java.util.Collections.singletonList("middle"));

    java.util.List<WeaverPlugin> removed = loader.unloadDependentsOf(java.util.Collections.singletonList(base));

    assertEquals(3, removed.size(), "the chain requiring the unloaded plugin kept running");
    assertTrue(topDestroyed, "the far end of the chain was not destroyed");
  }

  /**
   * A plugin that throws while it is destroyed is still removed. The failure is logged and the rest of the
   * chain keeps unloading.
   */
  @Test
  void aPluginThatThrowsWhileItIsDestroyedIsStillRemoved() throws Exception {
    WeaverPlugin base = plugin("base", false);
    WeaverPlugin brittle =
        new WeaverPlugin() {
          public String name() {
            return "brittle";
          }

          public void registerInterceptors(
              com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry r) {}

          public void destroy() {
            throw new IllegalStateException("cannot destroy");
          }
        };
    PluginLoader loader = new PluginLoader();
    java.lang.reflect.Field loaded = PluginLoader.class.getDeclaredField("loadedPlugins");
    loaded.setAccessible(true);
    ((java.util.List<WeaverPlugin>) loaded.get(loader)).addAll(java.util.Arrays.asList(base, brittle));
    java.lang.reflect.Field requirements = PluginLoader.class.getDeclaredField("requirements");
    java.util.Map<WeaverPlugin, java.util.List<String>> required =
        (java.util.Map<WeaverPlugin, java.util.List<String>>) Statics.read(requirements, loader);
    required.put(brittle, java.util.Collections.singletonList("base"));

    java.util.List<WeaverPlugin> removed =
        loader.unloadDependentsOf(java.util.Collections.singletonList(base));

    assertTrue(removed.contains(brittle), "a plugin that threw while being destroyed stayed loaded");
    assertFalse(((java.util.List<?>) loaded.get(loader)).contains(brittle));
  }

  /**
   * Unloading a plugin that this loader never activated still destroys it, and one that throws while it is
   * destroyed is reported rather than aborting the unload.
   */
  @Test
  void unloadingAPluginTheLoaderNeverActivatedStillDestroysIt() {
    WeaverPlugin[] destroyed = {null};
    WeaverPlugin outside =
        new WeaverPlugin() {
          public String name() {
            return "outside";
          }

          public void registerInterceptors(
              com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry r) {}

          public void destroy() {
            destroyed[0] = this;
            throw new IllegalStateException("cannot destroy");
          }
        };
    PluginLoader loader = new PluginLoader();

    assertDoesNotThrow(
        () -> loader.unloadDependentsOf(java.util.Collections.singletonList(outside)),
        "a plugin that threw while being destroyed aborted the unload");
    assertSame(outside, destroyed[0], "a plugin the loader never activated was not destroyed");
  }

  /**
   * Two plugins sharing a name both count as loaded, so unloading one of them leaves a plugin that requires
   * that name in place. A plugin whose requirement was not unloaded is walked and skipped too.
   */
  @Test
  void unloadingOneOfTwoPluginsSharingANameRemovesNothingElse() throws Exception {
    WeaverPlugin base = plugin("base", false);
    WeaverPlugin twin = plugin("base", false);
    WeaverPlugin needsBase = plugin("needs-base", false);
    WeaverPlugin needsOther = plugin("needs-other", false);
    PluginLoader loader = new PluginLoader();
    java.lang.reflect.Field loaded = PluginLoader.class.getDeclaredField("loadedPlugins");
    loaded.setAccessible(true);
    ((java.util.List<WeaverPlugin>) loaded.get(loader))
        .addAll(java.util.Arrays.asList(base, twin, needsBase, needsOther));
    java.lang.reflect.Field requirements = PluginLoader.class.getDeclaredField("requirements");
    java.util.Map<WeaverPlugin, java.util.List<String>> required =
        (java.util.Map<WeaverPlugin, java.util.List<String>>) Statics.read(requirements, loader);
    required.put(needsBase, java.util.Collections.singletonList("base"));
    required.put(needsOther, java.util.Collections.singletonList("other"));

    java.util.List<WeaverPlugin> removed = loader.unloadDependentsOf(java.util.Collections.singletonList(base));

    assertEquals(
        java.util.Collections.singletonList(base),
        removed,
        "unloading one of two plugins sharing a name took something else with it");
    assertTrue(((java.util.List<?>) loaded.get(loader)).contains(needsBase));
  }

  private boolean topDestroyed;

  /** A plugin with nothing but a name, optionally recording that it was destroyed. */
  private WeaverPlugin plugin(String pluginName, boolean recordDestruction) {
    return new WeaverPlugin() {
      public String name() {
        return pluginName;
      }

      public void registerInterceptors(
          com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry r) {}

      public void destroy() {
        if (recordDestruction) {
          topDestroyed = true;
        }
      }
    };
  }

  /**
   * A path this process cannot resolve has no canonical form, so the loader falls back to the absolute path,
   * and a jar that cannot be read is fingerprinted as never-loaded so the next scan retries it.
   */
  @Test
  void aPathThatCannotBeResolvedFallsBack() throws Exception {
    java.io.File unresolvable = new java.io.File("/tmp/weaver-unresolvable-" + (char) 0 + "x");
    java.lang.reflect.Method canonical =
        PluginLoader.class.getDeclaredMethod("canonical", java.io.File.class);
    canonical.setAccessible(true);
    assertEquals(
        unresolvable.getAbsolutePath(),
        canonical.invoke(null, unresolvable),
        "an unresolvable path was not replaced by its absolute path");

    java.lang.reflect.Method fingerprint =
        PluginLoader.class.getDeclaredMethod("fingerprint", java.io.File.class);
    fingerprint.setAccessible(true);
    long first = (Long) fingerprint.invoke(null, unresolvable);
    long second = (Long) fingerprint.invoke(null, unresolvable);
    assertNotEquals(
        first, second, "an unreadable jar was fingerprinted the same way twice, so it would look unchanged");
  }

  /**
   * A jar that declares a checksum its bytes do not match is refused before any of its classes load, and the
   * class loader the scanner opened for it is closed so the file is not pinned.
   */
  @Test
  void aJarWhoseChecksumDoesNotMatchIsRefused() throws Exception {
    pluginJar(
        "tampered.jar", "fixture.TamperedPlugin", "tampered", true, null, null, true, "deadbeef");
    PluginLoader loader = new PluginLoader();

    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertTrue(loaded.isEmpty(), "a jar with a checksum that does not match was loaded");
  }

  /** A directory that cannot be listed unloads every jar the loader remembers. */
  @Test
  void aDirectoryThatCannotBeListedUnloadsEverything() throws Exception {
    Path file = tempDir.resolve("not-a-directory");
    Files.write(file, new byte[] {1});

    pluginJar("solo.jar", "fixture.SoloPlugin", "solo", true, null, null);
    PluginLoader loader = new PluginLoader();
    loader.loadPluginsFromDirectory(
        tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertDoesNotThrow(() -> loader.unloadMissingJars(file.toString()));
  }

  /** The current span holder, except reading it fails outright. */
  private static ThreadLocal<Object> threadLocalThatFails() {
    return new ThreadLocal<Object>() {
      @Override
      public Object get() {
        throw new OutOfMemoryError("span unavailable");
      }
    };
  }

  /** One compiled plugin that reports itself enabled or not, packed into {@code tempDir}. */
  private void pluginJar(String fileName, String className, String pluginName, boolean enabled)
      throws Exception {
    pluginJar(fileName, className, pluginName, enabled, null, null);
  }

  /**
   * {@code depends} orders the plugin behind another, and {@code exports} publishes a package only a
   * plugin that depends on this one can see.
   */
  private void pluginJar(
      String fileName,
      String className,
      String pluginName,
      boolean enabled,
      String depends,
      String exports)
      throws Exception {
    pluginJar(fileName, className, pluginName, enabled, depends, exports, true);
  }

  /**
   * {@code depends} orders the plugin behind another, and {@code exports} publishes a package only a plugin
   * that depends on this one can see. {@code metadata} writes the properties file the loader reads; without
   * it the jar declares nothing at all.
   */
  private void pluginJar(
      String fileName,
      String className,
      String pluginName,
      boolean enabled,
      String depends,
      String exports,
      boolean metadata)
      throws Exception {
    pluginJar(fileName, className, pluginName, enabled, depends, exports, metadata, null);
  }

  /**
   * {@code checksum} is the digest the jar claims for itself. One that does not match the bytes makes the
   * loader refuse the jar before any of its classes run.
   */
  private void pluginJar(
      String fileName,
      String className,
      String pluginName,
      boolean enabled,
      String depends,
      String exports,
      boolean metadata,
      String checksum)
      throws Exception {
    String simple = className.substring(className.lastIndexOf('.') + 1);
    String dependsMethod =
        depends == null
            ? ""
            : "  public String[] depends() { return new String[] { \"" + depends + "\" }; }\n";
    String source =
        "package fixture;\n"
            + "public class "
            + simple
            + " implements com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin {\n"
            + "  public String name() { return \""
            + pluginName
            + "\"; }\n"
            + dependsMethod
            + "  public boolean isEnabled(com.github.cc11001100.weavergirl.api.plugin.PluginContext c)"
            + " { return "
            + enabled
            + "; }\n"
            + "  public void init(com.github.cc11001100.weavergirl.api.plugin.PluginContext c) {}\n"
            + "  public void registerInterceptors("
            + "com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry r) {}\n"
            + "}\n";
    Path src = Files.createTempDirectory("plugin-src");
    Path classes = Files.createTempDirectory("plugin-classes");
    try {
      Path javaFile = src.resolve(simple + ".java");
      Files.write(javaFile, source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
      java.util.jar.JarOutputStream out =
          new java.util.jar.JarOutputStream(Files.newOutputStream(jar));
      try {
        put(out, "fixture/" + simple + ".class", classes.resolve("fixture").resolve(simple + ".class"));
        put(
            out,
            "META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin",
            (className + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (metadata) {
          String properties = "plugin.name=" + pluginName + "\n";
          if (exports != null) {
            properties += "plugin.exports=" + exports + "\n";
          }
          if (checksum != null) {
            properties += "plugin.sha256=" + checksum + "\n";
          }
          put(
              out,
              "META-INF/weaver-girl-plugin.properties",
              properties.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
      } finally {
        out.close();
      }
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

  private static void delete(java.io.File file) {
    java.io.File[] children = file.listFiles();
    if (children != null) {
      for (java.io.File child : children) {
        delete(child);
      }
    }
    file.delete();
  }
}
