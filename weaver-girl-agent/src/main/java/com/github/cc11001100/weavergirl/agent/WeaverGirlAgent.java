// weaver-girl-agent/src/main/java/com/github/cc11001100/weavergirl/agent/WeaverGirlAgent.java
package com.github.cc11001100.weavergirl.agent;

import com.github.cc11001100.weavergirl.core.WeaverGirl;
import com.github.cc11001100.weavergirl.core.config.ConfigWatcher;
import com.github.cc11001100.weavergirl.core.config.WeaverConfig;
import com.github.cc11001100.weavergirl.core.config.YamlConfigLoader;
import com.github.cc11001100.weavergirl.core.event.JsonEventListener;
import com.github.cc11001100.weavergirl.core.plugin.PluginLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java Agent entry point.
 * Supports both premain (startup-time) and agentmain (runtime attach) modes.
 *
 * <p>Usage:</p>
 * <pre>
 *   java -javaagent:weaver-girl-agent.jar -jar app.jar
 *   java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml -jar app.jar
 *   java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml,watch=true -jar app.jar
 *   java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml,plugins=/path/to/plugins -jar app.jar
 * </pre>
 */
public class WeaverGirlAgent {

    private static final Logger log = LoggerFactory.getLogger(WeaverGirlAgent.class);
    private static final String CONFIG_PREFIX = "config=";
    private static volatile ConfigWatcher configWatcher;

    /**
     * Premain entry — called before application main() when using -javaagent flag.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        init(agentArgs, instrumentation, false);
    }

    /**
     * Agentmain entry — called when dynamically attaching to a running JVM.
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        init(agentArgs, instrumentation, true);
    }

    private static void init(String agentArgs, Instrumentation instrumentation, boolean isAttach) {
        try {
            // Banner with version
            String version = WeaverGirlAgent.class.getPackage().getImplementationVersion();
            if (version == null) version = "1.0.0-SNAPSHOT";
            log.info("WeaverGirl agent v{} initializing... ({})", version, isAttach ? "dynamic attach" : "premain");

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

            // Enable structured JSON event output if jsonEvents=true
            if ("true".equals(args.get("jsonEvents"))) {
                com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher.getInstance()
                        .addListener(new JsonEventListener());
                log.info("Structured JSON event output enabled (jsonEvents=true)");
            }

            // Determine YAML config path (before bootstrap so that
            // excludedClasses can be wired into the transformer's ignore matcher)
            String configPath = args.get("config");
            if (configPath == null && agentArgs != null && !agentArgs.isEmpty()
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

            // Now register interceptors from YAML config (registry is available)
            if (configPath != null) {
                YamlConfigLoader configLoader = new YamlConfigLoader();
                int beforeCount = weaverGirl.getRegistry().getAllDefinitions().size();
                configLoader.loadFromFile(configPath, weaverGirl.getRegistry());
                int afterCount = weaverGirl.getRegistry().getAllDefinitions().size();
                log.info("Loaded {} interceptor definitions from YAML config", afterCount - beforeCount);

                // Start config watcher if watch=true
                if ("true".equalsIgnoreCase(args.get("watch"))) {
                    configWatcher = new ConfigWatcher(configPath, weaverGirl.getRegistry());
                    configWatcher.setAfterReloadCallback(() -> weaverGirl.retransformLoadedClasses());
                    weaverGirl.setConfigWatcher(configWatcher);
                    configWatcher.start();
                }
            }

            // Load plugins from plugin directory if specified
            String pluginDir = args.get("plugins");
            if (pluginDir != null) {
                PluginLoader pluginLoader = new PluginLoader();
                pluginLoader.loadPluginsFromDirectory(pluginDir, weaverGirl.getRegistry(), args);
                log.info("Plugins loaded from directory: {}", pluginDir);
            }

            // If dynamically attached, retransform already-loaded classes
            if (isAttach) {
                int retransformed = weaverGirl.retransformLoadedClasses();
                log.info("Retransformed {} already-loaded classes for dynamic attach", retransformed);
            }

            // Register shutdown hook to cleanly destroy plugins
            final WeaverGirl shutdownRef = weaverGirl;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (configWatcher != null) {
                        configWatcher.stop();
                    }
                    shutdownRef.shutdown();
                } catch (Exception e) {
                    // Shutdown hook must not throw
                }
            }, "weaver-girl-shutdown"));

            log.info("WeaverGirl agent initialized with {} interceptor definitions",
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
     * Parse agent arguments in key=value format separated by commas.
     * Example: "config=/path/to/weaver.yml,watch=true"
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
}
