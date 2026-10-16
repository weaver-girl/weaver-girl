package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.PluginLogger;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The directory loader is the path a third-party product jar takes. These pin the two things that
 * path has to get right: plugins spread across jars still come up in dependency order, and a jar
 * that asks for a newer plugin contract than this agent offers is left unloaded.
 */
public class PluginContractLoadingTest {

  @TempDir Path tempDir;

  /** Tracking is static and survives the loader that recorded it, so one test must not see another's. */
  /**
   * Tracking is static and outlives the loader that recorded it. A plugin that registered through a
   * declared loader leaves that declaration behind, and the next test would have its own registrations
   * credited to a plugin that no longer exists.
   */
  @BeforeEach
  void noPluginIsTrackedYet() {
    DefaultInterceptorRegistry.clearTracking();
  }

  /**
   * A jar that declares {@code plugin.sha256} is refused when the content does not match, and that
   * refusal must not take the jar next to it with it. A jar that declares nothing still loads.
   */
  @Test
  void jarDeclaringTheWrongChecksumIsNotLoaded() throws Exception {
    pluginJar("good.jar", "com.example.GoodPlugin", "good", "new String[0]", null);
    File bad = pluginJar("bad.jar", "com.example.BadPlugin", "bad", "new String[0]");
    declareChecksum(bad, "0000000000000000000000000000000000000000000000000000000000000000");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertEquals(Collections.singletonList("good"), namesOf(loaded));
  }

  /** The same jar loads once the declared checksum is the digest of its own content. */
  @Test
  void jarDeclaringTheRightChecksumLoads() throws Exception {
    File jar = pluginJar("signed.jar", "com.example.SignedPlugin", "signed", "new String[0]");
    declareChecksum(jar, contentDigest(jar));

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertEquals(Collections.singletonList("signed"), namesOf(loaded));
  }

  /** Rewrite the jar's metadata so it declares {@code plugin.sha256}. The digest ignores that entry. */
  private static void declareChecksum(File jar, String sha256) throws Exception {
    java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
    java.util.jar.JarFile in = new java.util.jar.JarFile(jar);
    try {
      java.util.Enumeration<JarEntry> found = in.entries();
      while (found.hasMoreElements()) {
        JarEntry entry = found.nextElement();
        if (!entry.isDirectory()) {
          entries.put(entry.getName(), readFully(in.getInputStream(entry)));
        }
      }
    } finally {
      in.close();
    }
    byte[] metadata = entries.get("META-INF/weaver-girl-plugin.properties");
    entries.put(
        "META-INF/weaver-girl-plugin.properties",
        concat(metadata, ("plugin.sha256=" + sha256 + "\n").getBytes(StandardCharsets.UTF_8)));
    JarOutputStream out = new JarOutputStream(new FileOutputStream(jar));
    try {
      for (java.util.Map.Entry<String, byte[]> entry : entries.entrySet()) {
        putEntry(out, entry.getKey(), entry.getValue());
      }
    } finally {
      out.close();
    }
  }

  /** The digest the loader checks a declared {@code plugin.sha256} against. */
  private static String contentDigest(File jar) throws Exception {
    java.util.jar.JarFile in = new java.util.jar.JarFile(jar);
    try {
      return PluginVerifier.computeContentSha256(in);
    } finally {
      in.close();
    }
  }

  private static List<String> namesOf(List<WeaverPlugin> plugins) {
    List<String> names = new ArrayList<>();
    for (WeaverPlugin plugin : plugins) {
      names.add(plugin.name());
    }
    return names;
  }

  @Test
  void pluginsInSeparateJarsLoadInDependencyOrder() throws Exception {
    // base.jar sorts first alphabetically and declares no dependency of its own, so a loader that
    // sorts inside each jar would start it before extra.jar even though it depends on extra.
    pluginJar("base.jar", "com.example.BasePlugin", "base", "new String[] {\"extra\"}");
    pluginJar("extra.jar", "com.example.ExtraPlugin", "extra", "new String[0]");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertEquals(2, loaded.size(), "both jars loaded");
    assertEquals(Arrays.asList("extra", "base"), new ArrayList<>(PluginLoadOrder.ORDER));
  }

  /**
   * Parallel loading groups plugins by name, and that index used to keep one plugin per name. With
   * more than two plugins the loader takes the parallel path, so a third jar that reused a name was
   * never initialized.
   */
  @Test
  void duplicateNamesStillLoadWhenThereAreEnoughPluginsToGoParallel() throws Exception {
    pluginJar("a.jar", "com.example.PluginA", "metrics", "new String[0]");
    pluginJar("b.jar", "com.example.PluginB", "metrics", "new String[0]");
    pluginJar("c.jar", "com.example.PluginC", "metrics", "new String[0]");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertEquals(3, loaded.size(), "a shared name dropped a plugin from the parallel load");
    assertEquals(3, PluginLoadOrder.ORDER.size(), "a shared name skipped a plugin's init");
  }

  /**
   * A dependency names a plugin, and more than one plugin can carry that name. The dependent has to
   * wait for every one of them: the parallel loader used to remember a single plugin per name, so it
   * scheduled the dependent next to the copies it had forgotten.
   */
  @Test
  void dependentWaitsForEveryPluginSharingTheDependencyName() throws Exception {
    pluginJar("a.jar", "com.example.PluginA", "storage", "new String[0]");
    pluginJar("b.jar", "com.example.PluginB", "storage", "new String[0]");
    pluginJar("c.jar", "com.example.PluginC", "audit", "new String[] {\"storage\"}");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    loader.loadPluginsFromDirectory(
        tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    List<String> order = new ArrayList<>(PluginLoadOrder.ORDER);
    assertEquals(3, order.size(), "a plugin never initialized");
    assertTrue(
        order.indexOf("audit") > order.lastIndexOf("storage"),
        "audit initialized before every storage plugin: " + order);
  }

  /**
   * {@code depends()} returns an array, not a collection. The parallel loader used to read it by
   * reflection and only accept a {@code Collection}, which an array never is, so it saw no dependencies
   * at all and started every plugin in the same wave.
   */
  @Test
  void dependentLoadsInALaterWaveThanWhatItDependsOn() throws Exception {
    pluginJar("a.jar", "com.example.PluginA", "storage", "new String[0]");
    pluginJar("b.jar", "com.example.PluginB", "audit", "new String[] {\"storage\"}");
    pluginJar("c.jar", "com.example.PluginC", "report", "new String[] {\"audit\"}");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    loader.loadPluginsFromDirectory(
        tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    List<String> order = new ArrayList<>(PluginLoadOrder.ORDER);
    assertEquals(3, order.size(), "a plugin never initialized");
    assertTrue(
        order.indexOf("storage") < order.indexOf("audit")
            && order.indexOf("audit") < order.indexOf("report"),
        "a dependent started in the same wave as its dependency: " + order);
  }

  /**
   * A plugin can ignore the registry its context hands it and register on some other registry it can
   * reach — the annotation scanner keeps the one from {@code init} and registers through it later.
   * Disabling the plugin still has to remove those interceptors, which means the registration is
   * credited to the plugin by who was loading at the time, not by which registry object was used.
   */
  @Test
  void interceptorRegisteredOnARegistryThePluginReachedItselfIsStillItsOwn() throws Exception {
    sharedRegistry = new DefaultInterceptorRegistry();
    pluginJar("a.jar", "com.example.PluginA", "scanner", "new String[0]", "1.0.0", true);
    pluginJar("b.jar", "com.example.PluginB", "metrics", "new String[0]", "1.0.0", true);

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(tempDir.toString(), sharedRegistry, Collections.emptyMap());
    assertEquals(2, loaded.size());
    assertEquals(2, sharedRegistry.getAllDefinitions().size(), "both plugins registered");

    DefaultPluginManager manager = new DefaultPluginManager(loader, sharedRegistry);
    assertTrue(manager.disablePlugin("scanner"));

    List<String> remaining = new ArrayList<>();
    for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition def :
        sharedRegistry.getAllDefinitions()) {
      remaining.add(def.getName());
    }
    assertEquals(
        Collections.singletonList("metrics-hook"),
        remaining,
        "an interceptor registered on a registry the plugin reached itself survived its plugin being"
            + " disabled");
  }

  /**
   * Registering while the plugin loads is the easy case. A plugin can also register later, from a thread
   * it started itself — a config watcher, a scan that finishes in the background. Nothing about that
   * thread says which plugin it belongs to, so the registration has to be credited by the class that
   * made it.
   */
  @Test
  void interceptorRegisteredLaterFromThePluginsOwnThreadIsStillItsOwn() throws Exception {
    sharedRegistry = new DefaultInterceptorRegistry();
    pluginJar("a.jar", "com.example.PluginA", "scanner", "new String[0]", "1.0.0", false, true);
    pluginJar("b.jar", "com.example.PluginB", "metrics", "new String[0]");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(tempDir.toString(), sharedRegistry, Collections.emptyMap());
    assertEquals(2, loaded.size());

    // The plugin registered nothing while it loaded. It does that afterwards, on a thread of its own.
    for (WeaverPlugin plugin : loaded) {
      if ("scanner".equals(plugin.name())) {
        Thread thread = new Thread((Runnable) plugin);
        thread.start();
        thread.join(5000);
        assertFalse(thread.isAlive(), "the plugin's registration thread did not finish");
      }
    }
    assertEquals(1, sharedRegistry.getAllDefinitions().size(), "the late registration did not land");
    assertNotNull(
        sharedRegistry.getAllDefinitions().get(0).getOwner(), "the late registration has no owner");

    DefaultPluginManager manager = new DefaultPluginManager(loader, sharedRegistry);
    assertTrue(manager.disablePlugin("scanner"));
    assertTrue(
        sharedRegistry.getAllDefinitions().isEmpty(),
        "an interceptor registered from the plugin's own thread survived its plugin being disabled");
  }

  /**
   * A registration made entirely by a class that belongs to no plugin — a shared helper the plugin
   * delegates to — has no plugin class anywhere on the call stack, so there is nothing to credit it by.
   * The plugin names the helper's loader, and the registration is credited to it anyway.
   */
  @Test
  void interceptorRegisteredThroughASharedHelperIsCreditedToThePluginThatDeclaredIt()
      throws Exception {
    sharedRegistry = new DefaultInterceptorRegistry();
    pluginJar("a.jar", "com.example.PluginA", "scanner", "new String[0]", "1.0.0", false, false, true);
    pluginJar("b.jar", "com.example.PluginB", "metrics", "new String[0]", "1.0.0", false, false, true);

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(tempDir.toString(), sharedRegistry, Collections.emptyMap());
    assertEquals(2, loaded.size());

    // Only the scanner declared the helper class. Both registrations are made from a class nobody
    // declared and that is not this test: the test class is itself the declared helper, so a call made
    // from here would be credited to scanner however little it had to do with it.
    WeaverPlugin scanner = null;
    for (WeaverPlugin plugin : loaded) {
      if ("scanner".equals(plugin.name())) {
        scanner = plugin;
      }
    }
    DefaultInterceptorRegistry.track(scanner, SharedRegistrar.class);
    new RegistrarCaller().registerBoth(scanner);
    assertEquals(2, sharedRegistry.getAllDefinitions().size(), "both registrations did not land");

    Object scannerOwner = null;
    Object metricsOwner = null;
    for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition def :
        sharedRegistry.getAllDefinitions()) {
      if ("scanner-hook".equals(def.getName())) {
        scannerOwner = def.getOwner();
      } else if ("metrics-hook".equals(def.getName())) {
        metricsOwner = def.getOwner();
      }
    }
    assertNotNull(scannerOwner, "the declared helper's registration was not credited");
    assertEquals("scanner", ((WeaverPlugin) scannerOwner).name());
    assertNull(
        metricsOwner,
        "a registration no plugin asked for was credited to one: " + metricsOwner);

    DefaultPluginManager manager = new DefaultPluginManager(loader, sharedRegistry);
    assertTrue(manager.disablePlugin("scanner"));
    List<String> remaining = new ArrayList<>();
    for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition def :
        sharedRegistry.getAllDefinitions()) {
      remaining.add(def.getName());
    }
    assertEquals(
        Collections.singletonList("metrics-hook"),
        remaining,
        "disabling the plugin left the interceptor it registered through a shared helper: " + remaining);
  }

  /**
   * Declaring a helper class must not hand the plugin everything else that class's loader defines. Two
   * plugins loaded by one loader share it; one of them declares a helper, and a registration made by the
   * other plugin's own class still belongs to the other plugin.
   */
  @Test
  void declaringAHelperDoesNotClaimAnotherPluginThatSharesItsLoader() throws Exception {
    sharedRegistry = new DefaultInterceptorRegistry();
    // Both plugins in one jar, so one class loader defines them both. Neither registers while it loads.
    pluginJar("a.jar", "com.example.PluginA", "scanner", "new String[0]", "1.0.0", false, false, true);
    pluginJar(
        "a.jar", "com.example.PluginB", "metrics", "new String[0]", "1.0.0", false, true, false, true);

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(tempDir.toString(), sharedRegistry, Collections.emptyMap());
    assertEquals(2, loaded.size(), "a jar holding two plugins loaded only one");
    assertTrue(sharedRegistry.getAllDefinitions().isEmpty(), "a plugin registered before it was asked to");

    WeaverPlugin scanner = null;
    WeaverPlugin metrics = null;
    for (WeaverPlugin plugin : loaded) {
      if ("scanner".equals(plugin.name())) {
        scanner = plugin;
      } else if ("metrics".equals(plugin.name())) {
        metrics = plugin;
      }
    }
    assertNotNull(scanner);
    assertNotNull(metrics);
    assertSame(
        scanner.getClass().getClassLoader(),
        metrics.getClass().getClassLoader(),
        "the two plugins were not loaded by the same loader");

    // scanner claims the shared helper, then metrics registers through its own class, after that claim.
    DefaultInterceptorRegistry.track(scanner, SharedRegistrar.class);
    ((Runnable) metrics).run();

    Object metricsOwner = null;
    for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition def :
        sharedRegistry.getAllDefinitions()) {
      if ("metrics-hook".equals(def.getName())) {
        metricsOwner = def.getOwner();
      }
    }
    assertSame(
        metrics,
        metricsOwner,
        "a plugin's own registration was credited to the plugin that declared a helper from the same"
            + " loader");
  }

  /**
   * Two plugins may delegate to the same helper class, and both have to be able to say so. Each registration
   * is credited to the plugin that made the call, not to whichever plugin declared the helper last.
   */
  @Test
  void twoPluginsDeclaringTheSameHelperAreEachCreditedForTheirOwnRegistration() throws Exception {
    sharedRegistry = new DefaultInterceptorRegistry();
    pluginJar("a.jar", "com.example.PluginA", "scanner", "new String[0]", "1.0.0", false, false, true);
    pluginJar("b.jar", "com.example.PluginB", "metrics", "new String[0]", "1.0.0", false, false, true);

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(tempDir.toString(), sharedRegistry, Collections.emptyMap());
    assertEquals(2, loaded.size());

    WeaverPlugin scanner = null;
    WeaverPlugin metrics = null;
    for (WeaverPlugin plugin : loaded) {
      if ("scanner".equals(plugin.name())) {
        scanner = plugin;
      } else if ("metrics".equals(plugin.name())) {
        metrics = plugin;
      }
    }
    assertNotNull(scanner);
    assertNotNull(metrics);

    // metrics declares second. Under a single-valued declaration that would erase scanner's claim, and both
    // registrations would be credited to metrics.
    DefaultInterceptorRegistry.track(scanner, SharedRegistrar.class);
    DefaultInterceptorRegistry.track(metrics, SharedRegistrar.class);
    scanner.getClass().getMethod("registerThroughSharedHelper").invoke(scanner);
    metrics.getClass().getMethod("registerThroughSharedHelper").invoke(metrics);

    Object scannerOwner = null;
    Object metricsOwner = null;
    for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition def :
        sharedRegistry.getAllDefinitions()) {
      if ("scanner-hook".equals(def.getName())) {
        scannerOwner = def.getOwner();
      } else if ("metrics-hook".equals(def.getName())) {
        metricsOwner = def.getOwner();
      }
    }
    assertSame(scanner, scannerOwner, "the plugin that declared the helper first lost its registration");
    assertSame(metrics, metricsOwner, "the plugin that declared the helper second lost its registration");

    DefaultPluginManager manager = new DefaultPluginManager(loader, sharedRegistry);
    assertTrue(manager.disablePlugin("scanner"));
    List<String> remaining = new ArrayList<>();
    for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition def :
        sharedRegistry.getAllDefinitions()) {
      remaining.add(def.getName());
    }
    assertEquals(
        Collections.singletonList("metrics-hook"),
        remaining,
        "disabling one plugin removed the interceptor the other registered through the same helper: "
            + remaining);
  }

  /**
   * Two plugins can ship a class of the same name, each in its own loader, and both can declare the same
   * helper. A stack frame only carries the class name, so the credit has to come from the class object that
   * actually ran: otherwise both registrations land on whichever plugin's loader is asked first.
   */
  @Test
  void pluginsWithTheSameClassNameAreCreditedForTheirOwnRegistration() throws Exception {
    sharedRegistry = new DefaultInterceptorRegistry();
    pluginJar("a.jar", "com.example.Plugin", "scanner", "new String[0]", "1.0.0", false, false, true);
    pluginJar("b.jar", "com.example.Plugin", "metrics", "new String[0]", "1.0.0", false, false, true);

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(tempDir.toString(), sharedRegistry, Collections.emptyMap());
    assertEquals(2, loaded.size());

    WeaverPlugin scanner = null;
    WeaverPlugin metrics = null;
    for (WeaverPlugin plugin : loaded) {
      if ("scanner".equals(plugin.name())) {
        scanner = plugin;
      } else if ("metrics".equals(plugin.name())) {
        metrics = plugin;
      }
    }
    assertNotNull(scanner);
    assertNotNull(metrics);
    assertEquals(scanner.getClass().getName(), metrics.getClass().getName());
    assertNotSame(scanner.getClass(), metrics.getClass(), "the two plugins were defined by one loader");

    DefaultInterceptorRegistry.track(scanner, SharedRegistrar.class);
    DefaultInterceptorRegistry.track(metrics, SharedRegistrar.class);
    // Resolved through each plugin's own loader. Asking one plugin's class for the method hands the
    // signature's resolution to its loader, which can see the other plugin's class of the same name and
    // would dispatch both calls to that one.
    // The Method has to be the one resolved through the plugin's own loader. The two classes share a
    // name, and this test's loader defines the first of them, so a method looked up from here dispatches
    // both calls to that first class.
    scanner.getClass().getClassLoader().loadClass(scanner.getClass().getName())
        .getDeclaredMethod("registerThroughSharedHelper").invoke(scanner);
    metrics.getClass().getClassLoader().loadClass(metrics.getClass().getName())
        .getDeclaredMethod("registerThroughSharedHelper").invoke(metrics);

    Object scannerOwner = null;
    Object metricsOwner = null;
    for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition def :
        sharedRegistry.getAllDefinitions()) {
      if ("scanner-hook".equals(def.getName())) {
        scannerOwner = def.getOwner();
      } else if ("metrics-hook".equals(def.getName())) {
        metricsOwner = def.getOwner();
      }
    }
    assertSame(
        scanner, scannerOwner, "a registration was credited to the other plugin that shares its class name");
    assertSame(
        metrics, metricsOwner, "a registration was credited to the other plugin that shares its class name");
  }

  /**
   * Java 8 has no {@code StackWalker}, so the class object of a frame has to come from {@code
   * SecurityManager.getClassContext()} instead. A frame resolved by name lands on whichever plugin loader is
   * asked first, and both registrations would be credited to that one plugin.
   */
  @Test
  void sameNamedPluginsAreToldApartWithoutStackWalker() throws Exception {
    DefaultInterceptorRegistry.ignoreCallStack = true;
    try {
      pluginsWithTheSameClassNameAreCreditedForTheirOwnRegistration();
    } finally {
      DefaultInterceptorRegistry.ignoreCallStack = false;
    }
  }

  /**
   * A service interface does not have to live in the shared API. The plugin that offers it exports the
   * package, and a plugin that depends on it can load the interface and read what the provider registered.
   * A plugin that does not depend on it cannot see the package at all.
   */
  @Test
  void aPluginCanUseAServiceInterfaceItsDependencyExports() throws Exception {
    com.github.cc11001100.weavergirl.api.plugin.PluginServices.clear();
    exportingPluginJar(
        "clock.jar",
        "com.example.ClockPlugin",
        "clock",
        "new String[0]",
        "com.example.clock",
        "package com.example.clock;\n"
            + "public interface Clock { long now(); }\n",
        "package com.example.clock.internal;\n"
            + "public class Secret {}\n",
        "    c.registerService(com.example.clock.Clock.class, (com.example.clock.Clock) () -> 7L);\n");
    exportingPluginJar(
        "reader.jar",
        "com.example.ReaderPlugin",
        "reader",
        "new String[]{\"clock\"}",
        null,
        null,
        null,
        "    java.util.List<com.example.clock.Clock> clocks = "
            + "com.github.cc11001100.weavergirl.api.plugin.PluginServices.get(com.example.clock.Clock.class);\n"
            + "    if (clocks.size() != 1 || clocks.get(0).now() != 7L) {\n"
            + "      throw new IllegalStateException(\"clock was \" + clocks);\n"
            + "    }\n"
            + "    try {\n"
            + "      Class.forName(\"com.example.clock.internal.Secret\");\n"
            + "      throw new IllegalStateException(\"exporting a package exposed its subpackage\");\n"
            + "    } catch (ClassNotFoundException expected) {\n"
            + "      // com.example.clock.internal was not exported\n"
            + "    }\n");
    exportingPluginJar(
        "stranger.jar",
        "com.example.StrangerPlugin",
        "stranger",
        "new String[0]",
        null,
        null,
        null,
        "    try {\n"
            + "      Class.forName(\"com.example.clock.Clock\");\n"
            + "      throw new IllegalStateException(\"a plugin saw a package nobody exported to it\");\n"
            + "    } catch (ClassNotFoundException expected) {\n"
            + "      // the package belongs to the clock plugin, and this plugin does not depend on it\n"
            + "    }\n");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());
    assertEquals(3, loaded.size(), "a plugin failed to load: " + PluginLoadOrder.ORDER);
    assertTrue(
        PluginLoadOrder.ORDER.indexOf("clock") < PluginLoadOrder.ORDER.indexOf("reader"),
        "the plugin that exports the service initialized after the one that consumes it: "
            + PluginLoadOrder.ORDER);
  }

  /**
   * A plugin that {@code requires} another refuses to load when that one is absent, instead of starting and
   * then failing on the first call it cannot handle. {@code depends} only orders initialization, so a plugin
   * that merely depends on something missing still loads.
   */
  @Test
  void aPluginThatRequiresAMissingPluginDoesNotLoad() throws Exception {
    exportingPluginJar(
        "reader.jar",
        "com.example.ReaderPlugin",
        "reader",
        "new String[0]",
        "new String[]{\"clock\"}",
        null,
        null,
        null,
        null,
        null,
        null,
        "");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertTrue(loaded.isEmpty(), "a plugin loaded without what it requires: " + loaded);
    assertFalse(
        PluginLoadOrder.ORDER.contains("reader"), "the plugin initialized before its absence was noticed");
  }

  /**
   * A jar that ships no Java code of its own still has to be able to say it cannot run alone. {@code
   * plugin.requires} in the metadata names the same hard requirement {@code requires()} does, and a plugin
   * whose method returns nothing is kept out by the file alone.
   */
  @Test
  void aRequirementNamedInTheMetadataKeepsThePluginOut() throws Exception {
    exportingPluginJar(
        "reader.jar",
        "com.example.ReaderPlugin",
        "reader",
        "new String[0]",
        "new String[0]",
        "clock",
        null,
        null,
        null,
        null,
        null,
        "");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertTrue(loaded.isEmpty(), "metadata the plugin never reads was ignored: " + loaded);
    assertFalse(
        PluginLoadOrder.ORDER.contains("reader"),
        "the plugin initialized even though its metadata requires a plugin that is not there");
  }

  /**
   * A hard requirement is checked again when a plugin goes away, not only while the directory is scanned.
   * Unloading the plugin another one requires unloads that one too, because leaving it active means it keeps
   * intercepting calls it declared it cannot handle. A plugin that only {@code depends()} on the unloaded one
   * stays.
   */
  @Test
  void unloadingARequiredPluginUnloadsThePluginsThatRequireIt() throws Exception {
    exportingPluginJar(
        "clock.jar",
        "com.example.ClockPlugin",
        "clock",
        "new String[0]",
        "com.example.clock",
        "package com.example.clock;\n" + "public interface Clock { long now(); }\n",
        null,
        "");
    exportingPluginJar(
        "reader.jar",
        "com.example.ReaderPlugin",
        "reader",
        "new String[]{\"clock\"}",
        "new String[]{\"clock\"}",
        null,
        null,
        null,
        null,
        null,
        null,
        "");
    exportingPluginJar(
        "watcher.jar",
        "com.example.WatcherPlugin",
        "watcher",
        "new String[]{\"clock\"}",
        null,
        null,
        null,
        "");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());
    assertEquals(3, loaded.size(), "a plugin failed to load: " + PluginLoadOrder.ORDER);

    DefaultPluginManager manager = new DefaultPluginManager(loader, new DefaultInterceptorRegistry());
    assertTrue(manager.unloadPlugin("clock"));

    assertFalse(manager.isPluginActive("reader"), "a plugin stayed active without the plugin it requires");
    assertTrue(
        manager.isPluginActive("watcher"),
        "a plugin that only depends on the unloaded one was unloaded with it");
    assertEquals(1, manager.getActivePluginCount());
  }

  /**
   * Removing a jar is the other way a plugin goes away, and it has to carry the same consequence as unloading
   * it by name. A plugin in a different jar that requires the removed one can no longer run, so it is unloaded
   * too. One that only {@code depends()} on it stays, and so does a plugin whose requirement is still met by
   * another plugin of the same name.
   */
  @Test
  void removingAJarUnloadsThePluginsThatRequireItsPlugins() throws Exception {
    File clock =
        exportingPluginJar(
            "clock.jar",
            "com.example.ClockPlugin",
            "clock",
            "new String[0]",
            "com.example.clock",
            "package com.example.clock;\n" + "public interface Clock { long now(); }\n",
            null,
            "");
    exportingPluginJar(
        "reader.jar",
        "com.example.ReaderPlugin",
        "reader",
        "new String[]{\"clock\"}",
        "new String[]{\"clock\"}",
        null,
        null,
        null,
        null,
        null,
        null,
        "");
    exportingPluginJar(
        "watcher.jar",
        "com.example.WatcherPlugin",
        "watcher",
        "new String[]{\"clock\"}",
        null,
        null,
        null,
        "");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    assertEquals(
        3,
        loader
            .loadPluginsFromDirectory(
                tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap())
            .size(),
        "a plugin failed to load: " + PluginLoadOrder.ORDER);

    assertTrue(clock.delete(), "the test could not remove the jar");
    List<WeaverPlugin> removed = loader.unloadMissingJars(tempDir.toString());

    List<String> removedNames = new ArrayList<>();
    for (WeaverPlugin plugin : removed) {
      removedNames.add(plugin.name());
    }
    assertTrue(removedNames.contains("clock"), "removing the jar did not unload its plugin");
    assertTrue(
        removedNames.contains("reader"),
        "the plugin that requires the removed one stayed loaded: " + removedNames);
    List<String> stillLoaded = new ArrayList<>();
    for (WeaverPlugin plugin : loader.getLoadedPlugins()) {
      stillLoaded.add(plugin.name());
    }
    assertEquals(
        Collections.singletonList("watcher"),
        stillLoaded,
        "a plugin that only depends on the removed one was unloaded with it");
  }

  /**
   * Disabling is reversible, so it must not destroy the plugins that require the disabled one — but it also
   * must not leave them intercepting calls they declared they cannot handle. They are suspended, and enabling
   * the requirement again brings them back. Enabling a suspended plugin on its own does nothing, because the
   * requirement is still disabled.
   */
  @Test
  void disablingARequiredPluginSuspendsThePluginsThatRequireIt() throws Exception {
    exportingPluginJar(
        "clock.jar",
        "com.example.ClockPlugin",
        "clock",
        "new String[0]",
        "com.example.clock",
        "package com.example.clock;\n" + "public interface Clock { long now(); }\n",
        null,
        "");
    exportingPluginJar(
        "reader.jar",
        "com.example.ReaderPlugin",
        "reader",
        "new String[]{\"clock\"}",
        "new String[]{\"clock\"}",
        null,
        null,
        null,
        null,
        null,
        null,
        "");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    assertEquals(
        2,
        loader
            .loadPluginsFromDirectory(
                tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap())
            .size());

    DefaultPluginManager manager = new DefaultPluginManager(loader, new DefaultInterceptorRegistry());
    assertTrue(manager.disablePlugin("clock"));

    assertFalse(manager.isPluginActive("reader"), "a plugin stayed active without the plugin it requires");
    assertFalse(
        manager.enablePlugin("reader"),
        "a plugin suspended by a disabled requirement was enabled on its own");
    assertEquals(2, manager.getAllPluginInfo().size(), "disabling destroyed a plugin instead of suspending it");

    assertTrue(manager.enablePlugin("clock"));
    assertTrue(
        manager.isPluginActive("reader"),
        "enabling the requirement did not bring back the plugin that was suspended for it");
  }

  /**
   * Parallel loading groups plugins into waves, and a hard requirement has to put a plugin in a later wave than
   * the one it requires. Three plugins is what makes the loader parallel at all, so a plugin with no dependency
   * rides along. Without the requirement counting, reader initialises in the same wave as clock and can run
   * before the plugin it cannot run without.
   */
  @Test
  void aRequiredPluginInitializesBeforeThePluginThatRequiresIt() throws Exception {
    exportingPluginJar(
        "clock.jar",
        "com.example.ClockPlugin",
        "clock",
        "new String[0]",
        "com.example.clock",
        "package com.example.clock;\n" + "public interface Clock { long now(); }\n",
        null,
        "");
    exportingPluginJar(
        "reader.jar",
        "com.example.ReaderPlugin",
        "reader",
        "new String[0]",
        "new String[]{\"clock\"}",
        null,
        null,
        null,
        null,
        null,
        null,
        "");
    exportingPluginJar(
        "watcher.jar",
        "com.example.WatcherPlugin",
        "watcher",
        "new String[0]",
        null,
        null,
        null,
        "");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    assertEquals(
        3,
        loader
            .loadPluginsFromDirectory(
                tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap())
            .size(),
        "a plugin failed to load: " + PluginLoadOrder.ORDER);

    assertTrue(
        PluginLoadOrder.ORDER.indexOf("clock") < PluginLoadOrder.ORDER.indexOf("reader"),
        "the plugin that requires another initialized first: " + PluginLoadOrder.ORDER);
  }

  /**
   * The same ordering holds when the requirement is stated only in the jar metadata. A plugin that never
   * overrides {@code requires()} still has to wait for the plugin its {@code plugin.requires} names, because
   * the metadata is the only place that requirement exists.
   */
  @Test
  void aRequirementNamedInTheMetadataOrdersInitialization() throws Exception {
    exportingPluginJar(
        "clock.jar",
        "com.example.ClockPlugin",
        "clock",
        "new String[0]",
        "com.example.clock",
        "package com.example.clock;\n" + "public interface Clock { long now(); }\n",
        null,
        "");
    exportingPluginJar(
        "reader.jar",
        "com.example.ReaderPlugin",
        "reader",
        "new String[0]",
        "new String[0]",
        "clock",
        null,
        null,
        null,
        null,
        null,
        "");
    exportingPluginJar(
        "watcher.jar",
        "com.example.WatcherPlugin",
        "watcher",
        "new String[0]",
        null,
        null,
        null,
        "");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    assertEquals(
        3,
        loader
            .loadPluginsFromDirectory(
                tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap())
            .size(),
        "a plugin failed to load: " + PluginLoadOrder.ORDER);

    assertTrue(
        PluginLoadOrder.ORDER.indexOf("clock") < PluginLoadOrder.ORDER.indexOf("reader"),
        "metadata the loader never read was ignored when ordering: " + PluginLoadOrder.ORDER);
  }

  /**
   * Naming one class in {@code plugin.exports} exposes that class and nothing else in its package. An
   * implementation that sits next to the interface it serves would otherwise be reachable by every plugin that
   * depends on the provider.
   */
  @Test
  void exportingOneClassDoesNotExposeTheClassNextToIt() throws Exception {
    exportingPluginJar(
        "clock.jar",
        "com.example.ClockPlugin",
        "clock",
        "new String[0]",
        "new String[0]",
        null,
        "com.example.clock.Clock class",
        "com.example.clock",
        "com.example.clock",
        "package com.example.clock;\n" + "public interface Clock { long now(); }\n",
        "package com.example.clock;\n" + "public class SystemClock implements Clock { public long now() { return 1L; } }\n",
        "");
    exportingPluginJar(
        "reader.jar",
        "com.example.ReaderPlugin",
        "reader",
        "new String[]{\"clock\"}",
        "new String[0]",
        null,
        null,
        null,
        null,
        null,
        null,
        "    try {\n"
            + "      Class.forName(\"com.example.clock.Clock\");\n"
            + "    } catch (ClassNotFoundException e) {\n"
            + "      throw new IllegalStateException(\"the exported class was not visible\", e);\n"
            + "    }\n"
            + "    try {\n"
            + "      Class.forName(\"com.example.clock.SystemClock\");\n"
            + "      throw new IllegalStateException(\"exporting one class exposed the class next to it\");\n"
            + "    } catch (ClassNotFoundException expected) {\n"
            + "      // SystemClock shares the package but was not named\n"
            + "    }\n");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());
    assertEquals(2, loaded.size(), "a plugin failed to load: " + PluginLoadOrder.ORDER);
  }

  /**
   * Replacing an exporting jar gives it a new class loader. A plugin that already depends on it must be
   * pointed at that new loader: the export it was granted at startup names the loader of the version that
   * was just unloaded, and a class loaded through it would come from a jar that is no longer there.
   */
  @Test
  void replacingAnExportingJarRepointsThePluginsThatDependOnIt() throws Exception {
    exportingPluginJar(
        "clock.jar",
        "com.example.ClockPlugin",
        "clock",
        "new String[0]",
        "com.example.clock",
        "package com.example.clock;\n" + "public interface Clock { long now(); }\n",
        null,
        "");
    exportingPluginJar(
        "reader.jar",
        "com.example.ReaderPlugin",
        "reader",
        "new String[]{\"clock\"}",
        null,
        null,
        null,
        "");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());
    assertEquals(2, loaded.size(), "a plugin failed to load: " + PluginLoadOrder.ORDER);

    ClassLoader readerLoader = null;
    for (WeaverPlugin plugin : loader.getLoadedPlugins()) {
      if ("reader".equals(plugin.name())) {
        readerLoader = plugin.getClass().getClassLoader();
      }
    }
    Class<?> firstClock = readerLoader.loadClass("com.example.clock.Clock");

    // Same file name but different bytes, so the loader treats it as a new version and unloads the old one.
    // The added method is what makes the bytes differ; the class name stays, which is the case that matters.
    exportingPluginJar(
        "clock.jar",
        "com.example.ClockPlugin",
        "clock",
        "new String[0]",
        "com.example.clock",
        "package com.example.clock;\n" + "public interface Clock { long now(); long later(); }\n",
        null,
        "");
    loader.loadPluginsFromDirectory(
        tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    Class<?> secondClock = readerLoader.loadClass("com.example.clock.Clock");
    assertNotSame(
        firstClock,
        secondClock,
        "the dependent plugin still reads the export through the class loader that was unloaded");
    ClassLoader replacement = null;
    for (WeaverPlugin plugin : loader.getLoadedPlugins()) {
      if ("clock".equals(plugin.name())) {
        replacement = plugin.getClass().getClassLoader();
      }
    }
    assertSame(replacement, secondClock.getClassLoader(), "the export was not loaded by the replacement jar");
  }

  /**
   * One plugin offers a service, another consumes it, and neither names the other. Unloading the provider
   * has to take the service with it: a consumer that asks afterwards must not be handed a plugin that has
   * already been destroyed.
   */
  @Test
  void aServiceAPluginPublishesDisappearsWhenThePluginIsUnloaded() throws Exception {
    com.github.cc11001100.weavergirl.api.plugin.PluginServices.clear();
    pluginJar("a.jar", "com.example.PluginA", "clock", "new String[0]", "1.0.0", false, false, false, false, true);

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());
    assertEquals(1, loaded.size());

    List<Clock> clocks =
        com.github.cc11001100.weavergirl.api.plugin.PluginServices.get(Clock.class);
    assertEquals(1, clocks.size(), "the plugin's service was not published");
    assertEquals(5L, clocks.get(0).now());

    new DefaultPluginManager(loader, new DefaultInterceptorRegistry()).unloadPlugin("clock");

    assertTrue(
        com.github.cc11001100.weavergirl.api.plugin.PluginServices.get(Clock.class)
            .isEmpty(),
        "unloading the plugin left its service behind");
  }

  /** The registry the self-registering plugins reach, set by the test before the jars are compiled. */
  private static volatile DefaultInterceptorRegistry sharedRegistry;

  /**
   * A helper that belongs to no plugin. It registers on the shared registry, and the plugin that wants the
   * credit for that registration names this class's loader first.
   */
  public static final class SharedRegistrar {
    public static void register(String name) {
      registerDirectly(name);
    }

    /** Registers with no plugin class above it on the stack. The caller decides who is credited. */
    public static void registerDirectly(String name) {
      sharedRegistry.register(
          new com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition(
              name,
              new com.github.cc11001100.weavergirl.api.pointcut.Pointcut(
                  com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.byName("x"),
                  com.github.cc11001100.weavergirl.api.matcher.MethodMatcher.any()),
              new Noop()));
    }

    /** An interceptor with nothing to do. The registration is what the test watches, not the call. */
    public static final class Noop
        implements com.github.cc11001100.weavergirl.api.interceptor.Interceptor {}
  }

  /** The registry a self-registering test plugin registers on. */
  public static DefaultInterceptorRegistry registry() {
    return sharedRegistry;
  }

  /**
   * A plugin directory is scanned once at startup, so a jar copied in afterwards never loaded. Scanning
   * the directory again has to load the new jar and leave the one already loaded alone — loading it a
   * second time would register its interceptors twice.
   */
  @Test
  void jarAddedToTheDirectoryAfterStartupIsLoadedToo() throws Exception {
    pluginJar("a.jar", "com.example.PluginA", "storage", "new String[0]");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    assertEquals(
        1, loader.loadPluginsFromDirectory(tempDir.toString(), registry, Collections.emptyMap()).size());

    pluginJar("b.jar", "com.example.PluginB", "audit", "new String[0]");
    List<WeaverPlugin> second =
        loader.loadPluginsFromDirectory(tempDir.toString(), registry, Collections.emptyMap());

    assertEquals(2, second.size(), "the jar added after the first scan was not loaded");
    assertEquals(
        Arrays.asList("storage", "audit"),
        new ArrayList<>(PluginLoadOrder.ORDER),
        "the jar already loaded was initialized a second time");
  }

  /**
   * Deleting a jar from a watched directory has to unload the plugin it contributed. Leaving it loaded
   * means a plugin the operator removed keeps intercepting, and its interceptors stay registered.
   */
  @Test
  void jarRemovedFromTheDirectoryUnloadsItsPlugin() throws Exception {
    File jar = pluginJar("a.jar", "com.example.PluginA", "storage", "new String[0]", "1.0.0", true);
    pluginJar("b.jar", "com.example.PluginB", "audit", "new String[0]", "1.0.0", true);

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    sharedRegistry = registry;
    assertEquals(
        2, loader.loadPluginsFromDirectory(tempDir.toString(), registry, Collections.emptyMap()).size());
    assertEquals(2, registry.getAllDefinitions().size());

    assertTrue(jar.delete(), "the test could not remove the jar");
    List<WeaverPlugin> removed = loader.unloadMissingJars(tempDir.toString());

    assertEquals(1, removed.size(), "removing a jar did not unload its plugin");
    assertEquals("storage", removed.get(0).name());
    assertEquals(1, loader.getLoadedPlugins().size(), "the removed plugin is still loaded");
    List<String> remaining = new ArrayList<>();
    for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition def :
        registry.getAllDefinitions()) {
      remaining.add(def.getName());
    }
    assertEquals(
        Collections.singletonList("audit-hook"),
        remaining,
        "the removed plugin's interceptor stayed registered");
  }

  /**
   * Overwriting a jar in place keeps the path, so a loader that remembers jars by path treats the new
   * bytes as the plugin it already loaded and never reads them. The previous version has to be unloaded
   * and the new one loaded instead.
   */
  @Test
  void jarReplacedInPlaceLoadsTheNewVersion() throws Exception {
    pluginJar("a.jar", "com.example.PluginA", "storage", "new String[0]", "1.0.0", true);

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    sharedRegistry = registry;
    assertEquals(
        1, loader.loadPluginsFromDirectory(tempDir.toString(), registry, Collections.emptyMap()).size());
    assertEquals("storage-hook", registry.getAllDefinitions().get(0).getName());

    // Same file name, different plugin. The file is overwritten, not added.
    pluginJar("a.jar", "com.example.PluginB", "audit", "new String[0]", "1.0.0", true);
    List<WeaverPlugin> second =
        loader.loadPluginsFromDirectory(tempDir.toString(), registry, Collections.emptyMap());

    assertEquals(1, second.size(), "the replacement jar was not loaded");
    assertEquals("audit", second.get(0).name());
    assertEquals(1, loader.getLoadedPlugins().size(), "the replaced version is still loaded");
    assertEquals(
        "audit",
        loader.getLoadedPlugins().get(0).name(),
        "the loaded plugin is still the version that was replaced");
    List<String> remaining = new ArrayList<>();
    for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition def :
        registry.getAllDefinitions()) {
      remaining.add(def.getName());
    }
    assertEquals(
        Collections.singletonList("audit-hook"),
        remaining,
        "the replaced version's interceptor stayed registered: " + remaining);
    assertEquals(
        Arrays.asList("storage", "audit"),
        new ArrayList<>(PluginLoadOrder.ORDER),
        "the replacement was not initialized, or the old version was initialized again");
  }

  @Test
  void jarBuiltForNewerContractIsNotLoaded() throws Exception {
    pluginJar("future.jar", "com.example.FuturePlugin", "future", "new String[0]", "99.0.0");

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertTrue(loaded.isEmpty(), "a plugin that needs a newer contract must not be activated");
    assertFalse(PluginLoadOrder.ORDER.contains("future"));
  }

  @Test
  void jarWithoutContractDeclarationStillLoads() throws Exception {
    pluginJar("plain.jar", "com.example.PlainPlugin", "plain", "new String[0]", null);

    PluginLoadOrder.ORDER.clear();
    PluginLoader loader = new PluginLoader();
    List<WeaverPlugin> loaded =
        loader.loadPluginsFromDirectory(
            tempDir.toString(), new DefaultInterceptorRegistry(), Collections.emptyMap());

    assertEquals(1, loaded.size());
    assertEquals(Collections.singletonList("plain"), new ArrayList<>(PluginLoadOrder.ORDER));
  }

  @Test
  void pluginLoggerReachesTheAgentLog() {
    InterceptorRegistry registry = new DefaultInterceptorRegistry();
    PluginContext context = new DefaultPluginContext(registry, Collections.emptyMap(), "demo");

    PluginLogger logger = context.getLogger();
    assertNotNull(logger);
    assertDoesNotThrow(
        () -> {
          logger.info("hello");
          logger.warn("careful");
          logger.error("boom", new IllegalStateException("x"));
        });
  }

  private File pluginJar(String fileName, String className, String pluginName, String dependsExpr)
      throws Exception {
    return pluginJar(fileName, className, pluginName, dependsExpr, "1.0.0", false, false, false);
  }

  /**
   * Compile one plugin class and pack it, with its SPI entry and metadata, into a jar. {@code
   * apiVersion} null means the jar declares no contract version, as the built-in jars do.
   */
  private File pluginJar(
      String fileName, String className, String pluginName, String dependsExpr, String apiVersion)
      throws Exception {
    return pluginJar(fileName, className, pluginName, dependsExpr, apiVersion, false, false, false);
  }

  /**
   * Compile one plugin class and pack it, with its SPI entry and metadata, into a jar. {@code
   * apiVersion} null means the jar declares no contract version, as the built-in jars do. {@code
   * selfRegistering} makes the plugin ignore the registry it is handed and register on the static one
   * the test publishes, which is how a plugin reaches a registry on its own.
   */
  private File pluginJar(
      String fileName,
      String className,
      String pluginName,
      String dependsExpr,
      String apiVersion,
      boolean selfRegistering)
      throws Exception {
    return pluginJar(
        fileName, className, pluginName, dependsExpr, apiVersion, selfRegistering, false, false);
  }

  /**
   * {@code deferredRegistration} makes the plugin a {@code Runnable} that registers on the shared
   * registry when run, instead of while it loads. The test runs it on a thread of its own. {@code
   * delegatedRegistration} makes that run hand the registration to {@link SharedRegistrar}, a class that
   * belongs to no plugin, so the call stack carries nothing of the plugin's own.
   */
  private File pluginJar(
      String fileName,
      String className,
      String pluginName,
      String dependsExpr,
      String apiVersion,
      boolean selfRegistering,
      boolean deferredRegistration)
      throws Exception {
    return pluginJar(
        fileName,
        className,
        pluginName,
        dependsExpr,
        apiVersion,
        selfRegistering,
        deferredRegistration,
        false);
  }

  private File pluginJar(
      String fileName,
      String className,
      String pluginName,
      String dependsExpr,
      String apiVersion,
      boolean selfRegistering,
      boolean deferredRegistration,
      boolean delegatedRegistration)
      throws Exception {
    return pluginJar(
        fileName,
        className,
        pluginName,
        dependsExpr,
        apiVersion,
        selfRegistering,
        deferredRegistration,
        delegatedRegistration,
        false);
  }

  /**
   * {@code append} adds the plugin to a jar that already exists instead of replacing it, so both plugins
   * end up defined by the one class loader that jar is given.
   */
  private File pluginJar(
      String fileName,
      String className,
      String pluginName,
      String dependsExpr,
      String apiVersion,
      boolean selfRegistering,
      boolean deferredRegistration,
      boolean delegatedRegistration,
      boolean append)
      throws Exception {
    return pluginJar(
        fileName,
        className,
        pluginName,
        dependsExpr,
        apiVersion,
        selfRegistering,
        deferredRegistration,
        delegatedRegistration,
        append,
        false);
  }

  /**
   * A plugin that publishes a service of its own. {@code exportedPackage} is written into the jar metadata
   * as {@code plugin.exports}, and {@code exportedSource} is the interface that package contains, compiled
   * into the same jar. {@code hiddenSource} is a class in a subpackage, which the export must not expose.
   * {@code initBody} runs inside {@code init}, after the plugin has recorded that it loaded, so a consumer
   * can reach for the exported type there and fail the load if it cannot.
   */
  private File exportingPluginJar(
      String fileName,
      String className,
      String pluginName,
      String dependsExpr,
      String exportedPackage,
      String exportedSource,
      String hiddenSource,
      String initBody)
      throws Exception {
    return exportingPluginJar(
        fileName,
        className,
        pluginName,
        dependsExpr,
        "new String[0]",
        null,
        exportedPackage,
        exportedPackage,
        exportedPackage + ".internal",
        exportedSource,
        hiddenSource,
        initBody);
  }

  /**
   * {@code exportsValue} is written verbatim as {@code plugin.exports}, so a test can name one class with the
   * trailing {@code class} marker instead of a whole package. {@code hiddenPackage} is where {@code
   * hiddenSource} is compiled, which is the same package when the export must not spill onto the class next to
   * the one it names.
   */
  private File exportingPluginJar(
      String fileName,
      String className,
      String pluginName,
      String dependsExpr,
      String requiresExpr,
      String requiresMetadata,
      String exportsValue,
      String exportedPackage,
      String hiddenPackage,
      String exportedSource,
      String hiddenSource,
      String initBody)
      throws Exception {
    String source =
        "package com.example;\n"
            + "public class "
            + simpleName(className)
            + " implements com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin {\n"
            + "  public String name() { return \""
            + pluginName
            + "\"; }\n"
            + "  public String[] depends() { return "
            + dependsExpr
            + "; }\n"
            + "  public String[] requires() { return "
            + requiresExpr
            + "; }\n"
            + "  public void init(com.github.cc11001100.weavergirl.api.plugin.PluginContext c) {\n"
            + "    com.github.cc11001100.weavergirl.core.plugin.PluginLoadOrder.ORDER.add(name());\n"
            + initBody
            + "  }\n"
            + "  public void registerInterceptors("
            + "com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry r) {}\n"
            + "}\n";

    Path srcDir = Files.createTempDirectory("plugin-src");
    Path classesDir = Files.createTempDirectory("plugin-classes");
    try {
      // The exported type goes first: the plugin class names it, and javac only sees what is already
      // compiled into the output directory.
      compileInto(srcDir, classesDir, exportedSource, exportedPackage);
      compileInto(srcDir, classesDir, hiddenSource, hiddenPackage);
      Files.write(
          srcDir.resolve(simpleName(className) + ".java"), source.getBytes(StandardCharsets.UTF_8));
      // The compiler does not look in its own output directory, and it does not expand a classpath wildcard
      // the way the java launcher does. A provider names the type it just compiled, a consumer names the type
      // its dependency's jar exports, so both are listed explicitly. At runtime the plugin class loader
      // supplies the dependency instead, which is what the test checks.
      StringBuilder extraClasspath = new StringBuilder(classesDir.toString());
      if (exportedSource == null) {
        for (Path jar : Files.list(tempDir).collect(java.util.stream.Collectors.toList())) {
          extraClasspath.append(File.pathSeparator).append(jar);
        }
      }
      compile(
          srcDir.resolve(simpleName(className) + ".java"), classesDir, extraClasspath.toString());

      File jar = tempDir.resolve(fileName).toFile();
      JarOutputStream out = new JarOutputStream(new FileOutputStream(jar));
      try {
        java.util.Deque<Path> pending = new java.util.ArrayDeque<>();
        pending.add(classesDir);
        while (!pending.isEmpty()) {
          Path dir = pending.removeFirst();
          for (Path child : Files.list(dir).collect(java.util.stream.Collectors.toList())) {
            if (Files.isDirectory(child)) {
              pending.add(child);
            } else {
              putEntry(
                  out,
                  classesDir.relativize(child).toString().replace(File.separatorChar, '/'),
                  Files.readAllBytes(child));
            }
          }
        }
        putEntry(
            out,
            "META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin",
            (className + "\n").getBytes(StandardCharsets.UTF_8));
        String metadata =
            "plugin.name="
                + pluginName
                + "\nplugin.version=1.0.0\nplugin.apiVersion=1.0.0\n"
                + (requiresMetadata == null ? "" : "plugin.requires=" + requiresMetadata + "\n")
                + (exportsValue == null ? "" : "plugin.exports=" + exportsValue + "\n");
        putEntry(
            out, "META-INF/weaver-girl-plugin.properties", metadata.getBytes(StandardCharsets.UTF_8));
      } finally {
        out.close();
      }
      return jar;
    } finally {
      deleteRecursively(srcDir.toFile());
      deleteRecursively(classesDir.toFile());
    }
  }

  /**
   * {@code publishesService} makes the plugin publish a {@code Clock} through its context while it
   * initializes, which is how a plugin offers a capability other plugins can consume.
   */
  private File pluginJar(
      String fileName,
      String className,
      String pluginName,
      String dependsExpr,
      String apiVersion,
      boolean selfRegistering,
      boolean deferredRegistration,
      boolean delegatedRegistration,
      boolean append,
      boolean publishesService)
      throws Exception {
    String register =
        selfRegistering
            ? "  public void registerInterceptors("
                + "com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry r) {\n"
                + "    com.github.cc11001100.weavergirl.core.plugin.PluginContractLoadingTest"
                + ".registry().register(new com.github.cc11001100.weavergirl.api.interceptor"
                + ".InterceptorDefinition(\""
                + pluginName
                + "-hook\", new com.github.cc11001100.weavergirl.api.pointcut.Pointcut("
                + "com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.byName(\"x\"),"
                + "com.github.cc11001100.weavergirl.api.matcher.MethodMatcher.any()),"
                + "new Hook()));\n"
                + "  }\n"
                + "  public static class Hook implements "
                + "com.github.cc11001100.weavergirl.api.interceptor.Interceptor {}\n"
            : "  public void registerInterceptors("
                + "com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry r) {}\n";
    String implemented =
        deferredRegistration || delegatedRegistration
            ? "com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin, Runnable"
            : "com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin";
    String delegated =
        delegatedRegistration
            ? "  public void run() {\n"
                + "    com.github.cc11001100.weavergirl.core.plugin.PluginContractLoadingTest"
                + ".SharedRegistrar.register(\""
                + pluginName
                + "-hook\");\n"
                + "  }\n"
                + "  public void registerThroughSharedHelper() {\n"
                + "    com.github.cc11001100.weavergirl.core.plugin.PluginContractLoadingTest"
                + ".SharedRegistrar.registerDirectly(\""
                + pluginName
                + "-hook\");\n"
                + "  }\n"
            : "";
    String deferred =
        deferredRegistration
            ? "  public void run() {\n"
                + "    com.github.cc11001100.weavergirl.core.plugin.PluginContractLoadingTest"
                + ".registry().register(new com.github.cc11001100.weavergirl.api.interceptor"
                + ".InterceptorDefinition(\""
                + pluginName
                + "-hook\", new com.github.cc11001100.weavergirl.api.pointcut.Pointcut("
                + "com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.byName(\"x\"),"
                + "com.github.cc11001100.weavergirl.api.matcher.MethodMatcher.any()),"
                + "new Hook()));\n"
                + "  }\n"
                + "  public static class Hook implements "
                + "com.github.cc11001100.weavergirl.api.interceptor.Interceptor {}\n"
            : "";
    String source =
        "package com.example;\n"
            + "public class "
            + simpleName(className)
            + " implements "
            + implemented
            + " {\n"
            + delegated
            + deferred
            + "  public String name() { return \""
            + pluginName
            + "\"; }\n"
            + "  public String[] depends() { return "
            + dependsExpr
            + "; }\n"
            + "  public void init(com.github.cc11001100.weavergirl.api.plugin.PluginContext c) {\n"
            + "    com.github.cc11001100.weavergirl.core.plugin.PluginLoadOrder.ORDER.add(name());\n"
            + (publishesService
                ? "    c.registerService(com.github.cc11001100.weavergirl.core.plugin"
                    + ".Clock.class, new ClockImpl());\n"
                : "")
            + "  }\n"
            + (publishesService
                ? "  public static class ClockImpl implements com.github.cc11001100.weavergirl.core.plugin"
                    + ".Clock {\n"
                    + "    public long now() { return 5L; }\n"
                    + "  }\n"
                : "")
            + register
            + "}\n";

    Path srcDir = Files.createTempDirectory("plugin-src");
    Path classesDir = Files.createTempDirectory("plugin-classes");
    try {
      Path javaFile = srcDir.resolve(simpleName(className) + ".java");
      Files.write(javaFile, source.getBytes(StandardCharsets.UTF_8));

      compile(javaFile, classesDir);

      File jar = tempDir.resolve(fileName).toFile();
      // A second plugin packed into the same file shares its class loader with the first. The entries
      // already there are copied across, and the service list grows by one line.
      java.util.Map<String, byte[]> existing = new java.util.LinkedHashMap<>();
      if (append && jar.isFile()) {
        java.util.jar.JarFile previous = new java.util.jar.JarFile(jar);
        try {
          java.util.Enumeration<JarEntry> entries = previous.entries();
          while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
              existing.put(entry.getName(), readFully(previous.getInputStream(entry)));
            }
          }
        } finally {
          previous.close();
        }
      }
      String service = "META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin";
      byte[] serviceBytes = existing.getOrDefault(service, new byte[0]);
      existing.put(service, concat(serviceBytes, (className + "\n").getBytes(StandardCharsets.UTF_8)));

      JarOutputStream out = new JarOutputStream(new FileOutputStream(jar));
      try {
        // Every class file, not just the top-level one: a plugin that registers an interceptor of its
        // own compiles a second class, and leaving it out fails the plugin at load time.
        java.util.Deque<Path> pending = new java.util.ArrayDeque<>();
        pending.add(classesDir);
        while (!pending.isEmpty()) {
          Path dir = pending.removeFirst();
          for (Path child : Files.list(dir).collect(java.util.stream.Collectors.toList())) {
            if (Files.isDirectory(child)) {
              pending.add(child);
            } else {
              putEntry(
                  out,
                  classesDir.relativize(child).toString().replace(File.separatorChar, '/'),
                  Files.readAllBytes(child));
            }
          }
        }
        for (java.util.Map.Entry<String, byte[]> entry : existing.entrySet()) {
          putEntry(out, entry.getKey(), entry.getValue());
        }
        if (apiVersion != null && !existing.containsKey("META-INF/weaver-girl-plugin.properties")) {
          String metadata =
              "plugin.name="
                  + pluginName
                  + "\nplugin.version=1.0.0\nplugin.apiVersion="
                  + apiVersion
                  + "\n";
          putEntry(
              out, "META-INF/weaver-girl-plugin.properties", metadata.getBytes(StandardCharsets.UTF_8));
        }
      } finally {
        out.close();
      }
      return jar;
    } finally {
      deleteRecursively(srcDir.toFile());
      deleteRecursively(classesDir.toFile());
    }
  }

  /** Compile one source file into {@code classesDir}, under the package it declares. */
  private static void compileInto(Path srcDir, Path classesDir, String source, String packageName)
      throws Exception {
    if (source == null) {
      return;
    }
    String typeName = source.replaceAll("(?s).*\\b(class|interface) (\\w+).*", "$2");
    Path packageDir = srcDir.resolve(packageName.replace('.', File.separatorChar));
    Files.createDirectories(packageDir);
    Path file = packageDir.resolve(typeName + ".java");
    Files.write(file, source.getBytes(StandardCharsets.UTF_8));
    compile(file, classesDir, classesDir.toString());
  }

  private static void compile(Path javaFile, Path classesDir) throws Exception {
    compile(javaFile, classesDir, null);
  }

  /** {@code extraClasspath} is appended when the source names a type that lives in another plugin jar. */
  private static void compile(Path javaFile, Path classesDir, String extraClasspath) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "this test needs a JDK, not a JRE");
    String classpath =
        extraClasspath == null
            ? System.getProperty("java.class.path")
            : System.getProperty("java.class.path") + File.pathSeparator + extraClasspath;
    StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null);
    try {
      List<String> options =
          Arrays.asList("-classpath", classpath, "-d", classesDir.toString(), "--release", "8");
      StringWriter errors = new StringWriter();
      Boolean ok =
          compiler
              .getTask(
                  errors,
                  files,
                  null,
                  options,
                  null,
                  files.getJavaFileObjects(javaFile.toFile()))
              .call();
      assertEquals(Boolean.TRUE, ok, "plugin source failed to compile: " + errors);
    } finally {
      files.close();
    }
  }

  private static String simpleName(String className) {
    return className.substring(className.lastIndexOf('.') + 1);
  }

  private static void putEntry(JarOutputStream out, String name, byte[] bytes) throws Exception {
    out.putNextEntry(new JarEntry(name));
    out.write(bytes);
    out.closeEntry();
  }

  private static byte[] readFully(java.io.InputStream in) throws Exception {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int read;
    while ((read = in.read(buffer)) != -1) {
      out.write(buffer, 0, read);
    }
    in.close();
    return out.toByteArray();
  }

  private static byte[] concat(byte[] first, byte[] second) {
    byte[] both = new byte[first.length + second.length];
    System.arraycopy(first, 0, both, 0, first.length);
    System.arraycopy(second, 0, both, first.length, second.length);
    return both;
  }

  private static void deleteRecursively(File file) {
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteRecursively(child);
      }
    }
    file.delete();
  }
}
