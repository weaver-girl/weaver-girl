// weaver-girl-agent/src/main/java/com/github/cc11001100/weavergirl/agent/WeaverGirlAgent.java
package com.github.cc11001100.weavergirl.agent;

import com.github.cc11001100.weavergirl.core.WeaverGirl;
import com.github.cc11001100.weavergirl.core.config.WeaverConfig;
import com.github.cc11001100.weavergirl.core.config.YamlConfigLoader;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java Agent entry point. Supports both premain (startup-time) and agentmain (runtime attach)
 * modes.
 *
 * <p>Usage:
 *
 * <pre>
 *   java -javaagent:weaver-girl-agent.jar -jar app.jar
 *   java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml -jar app.jar
 *   java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml,watch=true -jar app.jar
 *   java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml,plugins=/path/to/plugins -jar app.jar
 *   java -javaagent:weaver-girl-agent.jar=annotationPackages=com.example.hooks;com.example.aspects -jar app.jar
 * </pre>
 */
public class WeaverGirlAgent {

  private static final Logger log = LoggerFactory.getLogger(WeaverGirlAgent.class);

  /**
   * Premain entry — called before application main() when using -javaagent flag.
   *
   * <p>Boots the agent body in an {@link
   * com.github.cc11001100.weavergirl.agent.classloader.AgentClassLoader isolated child-first
   * ClassLoader} so the agent's ByteBuddy and internals are not resolved against the host's system
   * ClassLoader. The advice-bridge classes stay parent-first on the system loader, so interception
   * wiring is unchanged. If the isolated loader cannot be set up, it falls back to the system
   * loader.
   */
  public static void premain(String agentArgs, Instrumentation instrumentation) {
    run(agentArgs, instrumentation, false);
  }

  /** Agentmain entry — called when dynamically attaching to a running JVM. */
  public static void agentmain(String agentArgs, Instrumentation instrumentation) {
    run(agentArgs, instrumentation, true);
  }

  /**
   * Boot the agent body in the isolated ClassLoader, invoking {@link #init} on the isolated copy of
   * this class. On any failure to build/use the isolated loader we fall back to running {@link
   * #init} directly on the system loader, so the agent always still functions.
   */
  private static void run(String agentArgs, Instrumentation instrumentation, boolean isAttach) {
    try {
      // Only the jars for enabled capabilities go on the isolated loader, so a capability that is
      // switched off is never loaded. The selection reads the config up front, but only as text:
      // parsing it into WeaverConfig here would define that class on the system loader and then
      // again inside the isolated loader, and the two copies are not the same class.
      java.util.List<java.io.File> pluginJars =
          selectPluginJars(readPluginSelection(agentArgs), WeaverGirlAgent.class);
      com.github.cc11001100.weavergirl.agent.classloader.AgentClassLoader iso =
          com.github.cc11001100.weavergirl.agent.classloader.AgentClassLoader.create(
              WeaverGirlAgent.class, pluginJars);
      Class<?> agentCls = Class.forName(WeaverGirlAgent.class.getName(), true, iso);
      java.lang.reflect.Method init =
          agentCls.getDeclaredMethod("init", String.class, Instrumentation.class, Boolean.TYPE);
      init.setAccessible(true);
      // While the isolated copy runs, its thread context loader is the isolated loader. Shaded
      // libraries (SnakeYAML resolving the YAML root type) load classes from the context loader
      // first and only then fall back to Class.forName, which would define them on the system
      // loader — a second copy of a class this loader already has, and a ClassCastException between
      // the two.
      ClassLoader previous = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(iso);
      try {
        init.invoke(null, agentArgs, instrumentation, isAttach);
      } finally {
        Thread.currentThread().setContextClassLoader(previous);
      }
      return;
    } catch (Throwable t) {
      // Isolation setup failed (e.g. agent jar location undetectable, or a class
      // could not be resolved in the isolated loader). Fall back to the system
      // loader so the agent still attaches. Use JUL (no slf4j dependency here).
      java.util.logging.Logger.getLogger(WeaverGirlAgent.class.getName())
          .warning("Isolated agent ClassLoader unavailable; running on the system loader: " + t);
    }
    init(agentArgs, instrumentation, isAttach);
  }

  private static void init(String agentArgs, Instrumentation instrumentation, boolean isAttach) {
    try {
      // Banner with version
      String version = WeaverGirlAgent.class.getPackage().getImplementationVersion();
      if (version == null) version = "1.0.0-SNAPSHOT";
      log.info(
          "WeaverGirl agent v{} initializing... ({})",
          version,
          isAttach ? "dynamic attach" : "premain");

      // Parse agent arguments
      Map<String, String> args = parseAgentArgs(agentArgs);
      if (agentArgs == null || agentArgs.isEmpty()) {
        log.info("No agent arguments provided — using built-in plugins only");
      }

      // Enable diagnostic mode if debug=true is passed
      if ("true".equals(args.get("debug"))) {
        System.setProperty("weavergirl.debug", "true");
        log.info("Diagnostic mode enabled (weavergirl.debug=true)");
      }

      // Emergency global kill-switch: start with interception disabled.
      // Triggered by agent arg emergencyDisable=true or -Dweavergirl.emergency.disable=true.
      // Flipped back on at runtime via POST /agent/interception on the health port.
      boolean emergencyDisable =
          "true".equalsIgnoreCase(args.get("emergencyDisable"))
              || "true".equalsIgnoreCase(System.getProperty("weavergirl.emergency.disable"));
      if (emergencyDisable) {
        com.github.cc11001100.weavergirl.core.InterceptorHolder.setInterceptionEnabled(
            false, "config:weavergirl.emergency.disable");
        log.warn("Agent starting with interception DISABLED (emergency mode)");
      }

      // Optional capabilities (jsonEvents, metricsPort, config watching, annotation scanning, the
      // management API) are plugins. They ship as their own jars and switch themselves on from these
      // same arguments, so nothing is wired here — a capability that is not configured never loads.

      // Determine YAML config path (before bootstrap so that
      // excludedClasses can be wired into the transformer's ignore matcher)
      String configPath = args.get("config");
      if (configPath == null
          && agentArgs != null
          && !agentArgs.isEmpty()
          && (agentArgs.endsWith(".yml") || agentArgs.endsWith(".yaml"))) {
        configPath = agentArgs;
      }

      // Parse config for agent-level settings (excludedClasses etc.)
      // without registering interceptors yet (registry not available until after bootstrap)
      WeaverConfig weaverConfig = null;
      if (configPath != null) {
        YamlConfigLoader configLoader = new YamlConfigLoader();
        weaverConfig = configLoader.parseFromFile(configPath);
        log.info("Config loaded from: {}", configPath);
      } else {
        log.info("No config file specified — built-in plugins will be used with defaults");
      }

      WeaverGirl weaverGirl = WeaverGirl.bootstrap(instrumentation, args, weaverConfig);

      // A config reload (the config-watch plugin) retransforms already-loaded classes through this
      // hook. The plugin has no agent instance of its own, so the agent installs the callback here,
      // once the transformer exists. It goes on InterceptorHolder because that class is resolved
      // parent-first: the plugin, loaded inside the isolated loader, and this code see the same one.
      com.github.cc11001100.weavergirl.core.InterceptorHolder.setRetransformHook(
          () -> weaverGirl.retransformLoadedClasses());

      // Now register interceptors from YAML config (registry is available)
      if (configPath != null) {
        YamlConfigLoader configLoader = new YamlConfigLoader();
        int beforeCount = weaverGirl.getRegistry().getAllDefinitions().size();
        configLoader.loadFromFile(configPath, weaverGirl.getRegistry());
        int afterCount = weaverGirl.getRegistry().getAllDefinitions().size();
        log.info("Loaded {} interceptor definitions from YAML config", afterCount - beforeCount);

        // Config hot-reload (watch=true) is the config-watch plugin's job. It reloads the YAML and
        // retransforms already-loaded classes itself, so there is nothing to start here.
      }

      // Drop-in plugins (plugins=<dir>) are loaded inside bootstrap, before the transformer is
      // installed. Loading them here would register interceptors the installed transformer never
      // applies.

      // @WeaveClass scanning (annotationPackages) is the annotation-scan plugin's job.

      // If dynamically attached, retransform already-loaded classes
      if (isAttach) {
        int retransformed = weaverGirl.retransformLoadedClasses();
        log.info("Retransformed {} already-loaded classes for dynamic attach", retransformed);
      }

      // The management REST API (apiPort) is the management-api plugin's job, the liveness and readiness
      // probes (healthPort) are the health plugin's, and /stats, /metrics and the interception toggle
      // (diagnosticsPort) are the diagnostics plugin's.

      // Register shutdown hook to cleanly destroy plugins
      final WeaverGirl shutdownRef = weaverGirl;
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      log.info("WeaverGirl agent shutting down...");
                      // The management API, the config watcher, the health probes and the diagnostics
                      // endpoint stop themselves: each is a plugin,
                      // and shutdown() destroys every loaded plugin. Drop the reload hook first so a
                      // reload racing the shutdown cannot retransform into a stopped agent.
                      com.github.cc11001100.weavergirl.core.InterceptorHolder.setRetransformHook(
                          null);
                      shutdownRef.shutdown();
                      log.info("WeaverGirl agent shutdown complete");
                    } catch (Exception e) {
                      // Shutdown hook must not throw
                      System.err.println("[weaver-girl] Error during shutdown: " + e.getMessage());
                    }
                  },
                  "weaver-girl-shutdown"));

      log.info(
          "WeaverGirl agent initialized with {} interceptor definitions",
          weaverGirl.getRegistry().getAllDefinitions().size());
    } catch (Throwable t) {
      // Agent init failure must NOT crash the target application
      // Log the error but allow the JVM to continue
      try {
        System.err.println("[weaver-girl] FATAL: Agent initialization failed: " + t.getMessage());
        t.printStackTrace(System.err);
      } catch (Exception e) {
        // Even logging failed — silently continue
      }
    }
  }

  /**
   * Parse agent arguments in key=value format separated by commas. Example:
   * "config=/path/to/weaver.yml,watch=true"
   */
  private static Map<String, String> parseAgentArgs(String agentArgs) {
    Map<String, String> result = new LinkedHashMap<>();
    if (agentArgs == null || agentArgs.isEmpty()) {
      return result;
    }
    // Handle key=value,key=value format
    String[] parts = agentArgs.split(",");
    for (String part : parts) {
      int eq = part.indexOf('=');
      if (eq > 0) {
        result.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
      } else {
        // Handle bare path (e.g., just a .yml file path)
        String trimmed = part.trim();
        if (trimmed.endsWith(".yml") || trimmed.endsWith(".yaml")) {
          result.put("config", trimmed);
        }
      }
    }
    return result;
  }

  /**
   * The capability jars that should be on the isolated loader for this launch.
   *
   * <p>Built-in capabilities ship as individual jars in a {@code plugins/} directory next to the
   * agent jar, and only the ones named by {@code selection} are returned. With neither key set every
   * jar is loaded, so the default behaviour is unchanged. Returns an empty list when no plugin
   * directory can be found (tests that run the agent straight from {@code target/classes}).
   *
   * @param selection {@code disabledPlugins} and {@code enabledPlugins}, already read as plain text
   */
  static java.util.List<java.io.File> selectPluginJars(Map<String, String> selection, Class<?> anchor) {
    java.io.File pluginDir = resolvePluginDirectory(anchor);
    if (pluginDir == null) {
      return java.util.Collections.emptyList();
    }
    java.io.File[] found =
        pluginDir.listFiles(
            (java.io.FileFilter) file -> file.isFile() && file.getName().endsWith(".jar"));
    if (found == null || found.length == 0) {
      return java.util.Collections.emptyList();
    }
    String disabled = selection.get("disabledPlugins");
    String enabled = selection.get("enabledPlugins");
    return com.github.cc11001100.weavergirl.core.plugin.PluginJarSelector.select(
        java.util.Arrays.asList(found), disabled, enabled);
  }

  /**
   * Read {@code disabledPlugins} / {@code enabledPlugins} as plain text, from the agent arguments
   * first and then, when a YAML config is named, from that file.
   *
   * <p>The YAML is read with a minimal line scan rather than {@link YamlConfigLoader}. This runs on
   * the system ClassLoader, before the isolated loader exists, and loading the config classes here
   * would define them twice — once on each loader — so the copy inside the agent would no longer be
   * the one the rest of the agent uses.
   */
  private static Map<String, String> readPluginSelection(String agentArgs) {
    Map<String, String> args = parseAgentArgs(agentArgs);
    Map<String, String> selection = new LinkedHashMap<>();
    if (args.get("disabledPlugins") != null) {
      selection.put("disabledPlugins", args.get("disabledPlugins"));
    }
    if (args.get("enabledPlugins") != null) {
      selection.put("enabledPlugins", args.get("enabledPlugins"));
    }
    String configPath = args.get("config");
    if (configPath == null) {
      return selection;
    }
    try {
      java.util.List<String> lines =
          java.nio.file.Files.readAllLines(java.nio.file.Paths.get(configPath));
      String key = null;
      java.util.List<String> values = null;
      for (String raw : lines) {
        String line = stripComment(raw);
        if (line.trim().isEmpty()) {
          continue;
        }
        if (!startsWithWhitespace(line)) {
          if (key != null) {
            selection.put(key, join(values));
          }
          key = null;
          int colon = line.indexOf(':');
          if (colon < 0) {
            continue;
          }
          String name = line.substring(0, colon).trim();
          String inline = line.substring(colon + 1).trim();
          if ("disabledPlugins".equals(name) || "enabledPlugins".equals(name)) {
            if (inline.isEmpty()) {
              key = name;
              values = new java.util.ArrayList<>();
            } else {
              selection.put(name, unquote(inline));
            }
          }
        } else if (key != null) {
          String item = line.trim();
          if (item.startsWith("-")) {
            item = item.substring(1).trim();
          }
          if (!item.isEmpty()) {
            values.add(unquote(item));
          }
        }
      }
      if (key != null) {
        selection.put(key, join(values));
      }
    } catch (Throwable t) {
      java.util.logging.Logger.getLogger(WeaverGirlAgent.class.getName())
          .warning("Could not read plugin selection from " + configPath + ": " + t.getMessage());
    }
    return selection;
  }

  private static String stripComment(String line) {
    boolean inQuote = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"' || c == '\'') {
        inQuote = !inQuote;
      } else if (c == '#' && !inQuote) {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static boolean startsWithWhitespace(String line) {
    return !line.isEmpty() && Character.isWhitespace(line.charAt(0));
  }

  private static String unquote(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '"' || first == '\'') && last == first) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }

  /** {@code plugins/} directory sitting next to the agent jar, or {@code null} when it isn't there. */
  private static java.io.File resolvePluginDirectory(Class<?> anchor) {
    java.security.CodeSource cs =
        anchor.getProtectionDomain() != null ? anchor.getProtectionDomain().getCodeSource() : null;
    java.net.URL location = cs != null ? cs.getLocation() : null;
    if (location == null || !"file".equals(location.getProtocol())) {
      return null;
    }
    java.io.File agentPath = new java.io.File(location.getPath());
    java.io.File parent = agentPath.isDirectory() ? agentPath.getParentFile() : agentPath.getParentFile();
    if (parent == null) {
      return null;
    }
    java.io.File pluginDir = new java.io.File(parent, "plugins");
    return pluginDir.isDirectory() ? pluginDir : null;
  }

  private static String join(java.util.List<String> values) {
    StringBuilder sb = new StringBuilder();
    for (String value : values) {
      if (value == null || value.trim().isEmpty()) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(value.trim());
    }
    return sb.toString();
  }

}
