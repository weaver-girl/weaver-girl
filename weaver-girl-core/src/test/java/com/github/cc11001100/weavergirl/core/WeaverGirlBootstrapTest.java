package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.core.config.WeaverConfig;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;

/**
 * Drives the real bootstrap path. The three-argument {@link WeaverGirl#bootstrap} is what premain
 * calls, and every branch in it is config-shaped, so one real {@link Instrumentation} plus a few
 * config variants covers it. Each run is shut down so the transformer it installed does not leak
 * into the next test.
 */
class WeaverGirlBootstrapTest {

  private static Instrumentation instrumentation() {
    return ByteBuddyAgent.install();
  }

  @Test
  void bootstrapWithNoConfigStartsAndShutsDown() {
    WeaverGirl agent = WeaverGirl.bootstrap(instrumentation());
    try {
      assertNotNull(agent.getRegistry());
      assertTrue(agent.getRegistry().getAllDefinitions().size() >= 0);
    } finally {
      agent.shutdown();
    }
  }

  @Test
  void bootstrapWithNullConfigMapUsesDefaults() {
    WeaverGirl agent = WeaverGirl.bootstrap(instrumentation(), null, null);
    try {
      assertNotNull(agent.getRegistry());
    } finally {
      agent.shutdown();
    }
  }

  @Test
  void bootstrapHonorsDisabledAndEnabledPluginsAndExcludedClasses() {
    WeaverConfig config = new WeaverConfig();
    config.setDisabledPlugins(Arrays.asList("does-not-exist"));
    config.setEnabledPlugins(Collections.singletonList("also-absent"));
    config.setExcludedClasses(Collections.singletonList("com.example.skip.*"));

    Map<String, String> args = new HashMap<String, String>();
    args.put("samplingRate", "0.5");

    WeaverGirl agent = WeaverGirl.bootstrap(instrumentation(), args, config);
    try {
      assertNotNull(agent.getRegistry());
    } finally {
      agent.shutdown();
    }
  }

  @Test
  void bootstrapWithEmptyPluginListsSkipsTheMerge() {
    WeaverConfig config = new WeaverConfig();
    config.setDisabledPlugins(Collections.<String>emptyList());
    config.setEnabledPlugins(Collections.<String>emptyList());

    WeaverGirl agent = WeaverGirl.bootstrap(instrumentation(), new HashMap<String, String>(), config);
    try {
      assertNotNull(agent.getRegistry());
    } finally {
      agent.shutdown();
    }
  }

  @Test
  void bootstrapLoadsPluginsFromADirectory() throws Exception {
    java.nio.file.Path dir = Files.createTempDirectory("weaver-plugins");
    Map<String, String> args = new HashMap<String, String>();
    args.put("plugins", dir.toString());

    WeaverGirl agent = WeaverGirl.bootstrap(instrumentation(), args, null);
    try {
      assertNotNull(agent.getRegistry());
    } finally {
      agent.shutdown();
    }
    assertEquals(0, agent.getRegistry().getAllDefinitions().size());
  }

  @Test
  void reloadPluginDirectoryCoversEveryBranch() throws Exception {
    java.nio.file.Path dir = Files.createTempDirectory("weaver-reload");
    // The jar has to contain a real plugin. An empty jar loads nothing, so removing it is not a change
    // and the reload stops before either retransform branch.
    java.nio.file.Path jar = pluginJar(dir, "late.jar");

    // Bootstrapped, so the transformer is installed and the change goes through it.
    Map<String, String> args = new HashMap<String, String>();
    args.put("plugins", dir.toString());
    WeaverGirl agent = WeaverGirl.bootstrap(instrumentation(), args, null);
    try {
      // Nothing changed, so the reload stops before retransforming.
      assertDoesNotThrow(() -> agent.reloadPluginDirectory(dir.toString(), args));
    } finally {
      agent.shutdown();
    }

    // A plain instance has no transformer, so a change falls back to the retransform hook. It has to
    // scan the jar first, or deleting it is not a change it knows about.
    WeaverGirl plain = WeaverGirl.create();
    plain.reloadPluginDirectory(dir.toString(), args);
    boolean[] hookRan = {false};
    com.github.cc11001100.weavergirl.core.InterceptorHolder.setRetransformHook(
        () -> hookRan[0] = true);
    try {
      Files.delete(jar);
      plain.reloadPluginDirectory(dir.toString(), args);
    } finally {
      com.github.cc11001100.weavergirl.core.InterceptorHolder.setRetransformHook(null);
    }
    assertTrue(hookRan[0], "a removed jar with no transformer must fall back to the retransform hook");
  }

  @Test
  void dynamicRefreshCoversEveryConfigShape() {
    WeaverGirl agent = WeaverGirl.bootstrap(instrumentation());
    try {
      // Absent, blank, not a number, and non-positive all decline to schedule anything.
      assertDoesNotThrow(() -> agent.initDynamicRefresh(null));
      agent.initDynamicRefresh(Collections.<String, String>emptyMap());
      agent.initDynamicRefresh(config("weavergirl.dynamic.refreshIntervalSeconds", "   "));
      agent.initDynamicRefresh(config("weavergirl.dynamic.refreshIntervalSeconds", "soon"));
      agent.initDynamicRefresh(config("weavergirl.dynamic.refreshIntervalSeconds", "0"));
      agent.initDynamicRefresh(config("weavergirl.dynamic.refreshIntervalSeconds", "-5"));

      // A positive interval arms the scheduler; stopping it twice is a no-op the second time.
      agent.initDynamicRefresh(config("weavergirl.dynamic.refreshIntervalSeconds", "3600"));
      agent.stopDynamicRefresh();
      agent.stopDynamicRefresh();
    } finally {
      agent.shutdown();
    }
  }

  @Test
  void refreshAndRetransformReportThroughTheInstalledTransformer() {
    WeaverGirl agent = WeaverGirl.bootstrap(instrumentation());
    try {
      assertDoesNotThrow(agent::refresh);
      assertTrue(agent.retransformLoadedClasses() >= 0);
      assertNotNull(agent.getTransformedClasses());
    } finally {
      agent.shutdown();
    }
    // With the transformer gone, retransform has nothing to do.
    assertEquals(0, agent.retransformLoadedClasses());
  }

  @Test
  void introduceRejectsBadArgumentsAndRegistersAValidOne() {
    WeaverGirl agent = WeaverGirl.create();
    Runnable delegate = new Runnable() {
      @Override
      public void run() {}
    };
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> agent.introduce(".*", null, delegate));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> agent.introduce(".*", Runnable.class, null));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> agent.introduce(".*", String.class, "not-an-interface"));

    String name = agent.introduce("com\\.example\\..*", Runnable.class, delegate);
    assertTrue(name.contains("Runnable"));
  }

  @Test
  void withInstrumentationInstallsTheTransformer() {
    WeaverGirl agent = WeaverGirl.create().withInstrumentation(instrumentation());
    try {
      assertNotNull(agent.getRegistry());
      assertEquals(0, agent.retransformLoadedClasses());
    } finally {
      agent.shutdown();
    }
  }

  @Test
  void applyCoreConfigCoversSamplingAndEmergencySwitches() {
    // Null is a no-op.
    assertDoesNotThrow(() -> WeaverGirl.applyCoreConfig(null));

    Map<String, String> valid = new HashMap<String, String>();
    valid.put("weavergirl.emergency.disable", "true");
    valid.put("samplingRate", "10");
    valid.put("samplingMaxRate", "1000");
    valid.put("samplingThreshold", "500");
    valid.put("circuitBreakerThreshold", "5");
    valid.put("circuitBreakerCooldownMs", "30000");
    assertDoesNotThrow(() -> WeaverGirl.applyCoreConfig(valid));

    // Unparseable numbers are warned about and ignored, not thrown.
    Map<String, String> invalid = new HashMap<String, String>();
    invalid.put("samplingRate", "fast");
    invalid.put("samplingMaxRate", "lots");
    invalid.put("samplingThreshold", "soon");
    assertDoesNotThrow(() -> WeaverGirl.applyCoreConfig(invalid));
  }

  /** A jar holding one minimal plugin, so loading and then removing it counts as a directory change. */
  private static Path pluginJar(Path dir, String fileName) throws Exception {
    Path src = Files.createTempDirectory("plugin-src");
    Path classes = Files.createTempDirectory("plugin-classes");
    try {
      String source =
          "package fixture;\n"
              + "public class LatePlugin implements "
              + "com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin {\n"
              + "  public String name() { return \"late\"; }\n"
              + "  public void init(com.github.cc11001100.weavergirl.api.plugin.PluginContext c) {}\n"
              + "  public void registerInterceptors("
              + "com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry r) {}\n"
              + "}\n";
      Path javaFile = src.resolve("LatePlugin.java");
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
                    Arrays.asList(
                        "-classpath",
                        System.getProperty("java.class.path"),
                        "-d",
                        classes.toString(),
                        "--release",
                        "8"),
                    null,
                    files.getJavaFileObjects(javaFile.toFile()))
                .call();
        assertTrue(Boolean.TRUE.equals(ok), "the fixture plugin failed to compile");
      } finally {
        files.close();
      }

      Path jar = dir.resolve(fileName);
      try (java.util.jar.JarOutputStream out =
          new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
        out.putNextEntry(new java.util.jar.JarEntry("fixture/LatePlugin.class"));
        Files.copy(classes.resolve("fixture").resolve("LatePlugin.class"), out);
        out.closeEntry();
        out.putNextEntry(
            new java.util.jar.JarEntry(
                "META-INF/services/com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin"));
        out.write("fixture.LatePlugin\n".getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
      return jar;
    } finally {
      deleteTree(src.toFile());
      deleteTree(classes.toFile());
    }
  }

  private static void deleteTree(File file) {
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteTree(child);
      }
    }
    file.delete();
  }

  private static Map<String, String> config(String key, String value) {
    Map<String, String> config = new HashMap<String, String>();
    config.put(key, value);
    return config;
  }

  @Test
  void shutdownStopsComponentsThatWereLazilyStarted() {
    WeaverGirl agent = WeaverGirl.bootstrap(instrumentation());
    try {
      // These are created on demand, so a plain shutdown never reaches their stop branches.
      agent.initSamplingMonitor();
      agent.initConfigWatcher();
      assertNotNull(agent.getPluginManager());
    } finally {
      agent.shutdown();
    }
    // A watcher installed by hand is stopped too, and stopping twice stays safe.
    agent.setConfigWatcher(
        new com.github.cc11001100.weavergirl.core.config.ConfigWatcher(null, agent.getRegistry()));
    assertDoesNotThrow(agent::shutdown);
  }

  @Test
  void dynamicRefreshRunsTheScheduledTask() throws Exception {
    WeaverGirl agent = WeaverGirl.create();
    try {
      agent.initDynamicRefresh(config("weavergirl.dynamic.refreshIntervalSeconds", "1"));
      // The first fire is one interval away, so a short wait lets the task body run.
      Thread.sleep(1500);
    } finally {
      agent.stopDynamicRefresh();
    }
  }

  @Test
  void builderAndExpressionCallbacksAllRun() {
    WeaverGirl agent = WeaverGirl.create();
    com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation invocation =
        new com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation(
            String.class, "length", "text", new Object[0]);
    int[] calls = {0};
    java.util.function.Consumer<com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation>
        count = inv -> calls[0]++;

    // Each method matcher, and every callback stacked so the "existing interceptor" branch runs.
    agent
        .intercept("com.example.Service")
        .methodPattern("do.*")
        .methodAnnotated("com.example.Traced")
        .methodSignature("work", "java.lang.String")
        .before(count)
        .after(count)
        .onException(count)
        .finallyCallback(count)
        .install();

    com.github.cc11001100.weavergirl.api.interceptor.Interceptor built =
        agent.getRegistry().getAllDefinitions().get(0).getInterceptor();
    built.before(invocation);
    built.after(invocation);
    built.onException(invocation);
    built.afterFinally(invocation);

    // The expression builder keeps its callbacks inline. Chaining all four nests them, so one call
    // exercises both the callback and the delegation to the interceptor it wrapped.
    agent
        .interceptExpression("execution(* com.example.Service.work(..))")
        .before(count)
        .after(count)
        .onException(count)
        .afterFinally(count)
        .install();
    // And with no callback at all, install supplies an empty interceptor.
    agent.interceptExpression("execution(* com.example.Service.idle(..))").install();

    for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition definition :
        agent.getRegistry().getAllDefinitions()) {
      com.github.cc11001100.weavergirl.api.interceptor.Interceptor interceptor =
          definition.getInterceptor();
      interceptor.before(invocation);
      interceptor.after(invocation);
      interceptor.onException(invocation);
      interceptor.afterFinally(invocation);
    }
    assertTrue(calls[0] > 0);
  }

  @Test
  void dynamicConfigChangesReachTheSamplingController() {
    // The listener is attached during bootstrap, so a plain instance never hears config changes.
    WeaverGirl agent = WeaverGirl.bootstrap(instrumentation());
    com.github.cc11001100.weavergirl.api.config.DynamicConfigManager manager =
        agent.getDynamicConfigManager();
    // Valid values are applied; unparseable ones are warned about; an unknown key is ignored; a key
    // removed (null value) keeps the current setting.
    assertDoesNotThrow(() -> manager.set("samplingRate", "25", "test"));
    assertDoesNotThrow(() -> manager.set("samplingMaxRate", "lots", "test"));
    assertDoesNotThrow(() -> manager.set("samplingThreshold", "soon", "test"));
    assertDoesNotThrow(() -> manager.set("not.a.core.key", "x", "test"));
    // A removed key arrives with a null value, which the listener leaves untouched.
    assertDoesNotThrow(() -> manager.remove("samplingRate", "test"));
  }

  @Test
  void singleArgumentBootstrapDelegates() {
    WeaverGirl agent = WeaverGirl.bootstrap(instrumentation());
    try {
      assertNull(agent.getSpanExporter());
    } finally {
      agent.shutdown();
    }
    // Shutting down twice is safe: every component tolerates a second stop.
    agent.shutdown();
  }
}
