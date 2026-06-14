// weaver-girl-agent/src/main/java/com/github/cc11001100/weavergirl/agent/WeaverGirlAgent.java
package com.github.cc11001100.weavergirl.agent;

import com.github.cc11001100.weavergirl.core.WeaverGirl;
import com.github.cc11001100.weavergirl.core.config.ConfigWatcher;
import com.github.cc11001100.weavergirl.core.config.WeaverConfig;
import com.github.cc11001100.weavergirl.core.config.YamlConfigLoader;
import com.github.cc11001100.weavergirl.core.event.JsonEventListener;
import com.github.cc11001100.weavergirl.core.metrics.PrometheusExporter;
import com.github.cc11001100.weavergirl.core.plugin.PluginLoader;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;
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
 *   java -javaagent:weaver-girl-agent.jar=annotationPackages=com.example.hooks;com.example.aspects -jar app.jar
 * </pre>
 */
public class WeaverGirlAgent {

    private static final Logger log = LoggerFactory.getLogger(WeaverGirlAgent.class);
    private static final String CONFIG_PREFIX = "config=";
    private static volatile ConfigWatcher configWatcher;
    private static volatile HttpServer healthServer;
    private static volatile WeaverGirl weaverGirlInstance;
    private static volatile long startTimeMs = System.currentTimeMillis();

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

            // Emergency global kill-switch: start with interception disabled.
            // Triggered by agent arg emergencyDisable=true or -Dweavergirl.emergency.disable=true.
            // Flipped back on at runtime via POST /agent/interception on the health port.
            boolean emergencyDisable = "true".equalsIgnoreCase(args.get("emergencyDisable"))
                    || "true".equalsIgnoreCase(System.getProperty("weavergirl.emergency.disable"));
            if (emergencyDisable) {
                com.github.cc11001100.weavergirl.core.InterceptorHolder
                        .setInterceptionEnabled(false, "config:weavergirl.emergency.disable");
                log.warn("Agent starting with interception DISABLED (emergency mode)");
            }

            // Enable structured JSON event output if jsonEvents=true
            if ("true".equals(args.get("jsonEvents"))) {
                com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher.getInstance()
                        .addListener(new JsonEventListener());
                log.info("Structured JSON event output enabled (jsonEvents=true)");
            }

            // Enable Prometheus metrics endpoint if metricsPort is specified
            String metricsPortStr = args.get("metricsPort");
            if (metricsPortStr != null) {
                try {
                    int metricsPort = Integer.parseInt(metricsPortStr);
                    PrometheusExporter exporter = new PrometheusExporter();
                    com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher.getInstance()
                            .addListener(exporter);
                    exporter.start(metricsPort);
                    log.info("Prometheus metrics endpoint enabled on port {} (metricsPort={})", metricsPort, metricsPortStr);
                } catch (NumberFormatException e) {
                    log.warn("Invalid metricsPort value: {}, expected integer", metricsPortStr);
                } catch (Exception e) {
                    log.warn("Failed to start Prometheus metrics server: {}", e.getMessage());
                }
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
            weaverGirlInstance = weaverGirl;

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

            // Scan for @WeaveClass-annotated interceptors if annotationPackages is specified
            // Usage: -javaagent:weaver-girl-agent.jar=annotationPackages=com.example.interceptors,com.example.aspects
            String annotationPackages = args.get("annotationPackages");
            if (annotationPackages != null && !annotationPackages.isEmpty()) {
                String[] packages = annotationPackages.split(";");
                com.github.cc11001100.weavergirl.core.scanner.AnnotatedClassScanner scanner =
                        new com.github.cc11001100.weavergirl.core.scanner.AnnotatedClassScanner();
                int found = scanner.scanAndLoad(weaverGirl.getRegistry(),
                        java.util.Arrays.stream(packages).map(String::trim).toArray(String[]::new));
                log.info("Scanned {} annotation package(s), loaded {} @WeaveClass interceptors",
                        packages.length, found);
            }

            // If dynamically attached, retransform already-loaded classes
            if (isAttach) {
                int retransformed = weaverGirl.retransformLoadedClasses();
                log.info("Retransformed {} already-loaded classes for dynamic attach", retransformed);
            }

            // Start health check endpoint if healthPort is specified
            String healthPortStr = args.get("healthPort");
            if (healthPortStr != null) {
                try {
                    int healthPort = Integer.parseInt(healthPortStr);
                    startHealthEndpoint(healthPort);
                    log.info("Health check endpoint enabled on port {} (healthPort={})", healthPort, healthPortStr);
                } catch (NumberFormatException e) {
                    log.warn("Invalid healthPort value: {}, expected integer", healthPortStr);
                } catch (Exception e) {
                    log.warn("Failed to start health check server: {}", e.getMessage());
                }
            }

            // Register shutdown hook to cleanly destroy plugins
            final WeaverGirl shutdownRef = weaverGirl;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    log.info("WeaverGirl agent shutting down...");
                    if (healthServer != null) {
                        healthServer.stop(2);
                        log.info("Health check endpoint stopped");
                    }
                    if (configWatcher != null) {
                        configWatcher.stop();
                    }
                    shutdownRef.shutdown();
                    log.info("WeaverGirl agent shutdown complete");
                } catch (Exception e) {
                    // Shutdown hook must not throw
                    System.err.println("[weaver-girl] Error during shutdown: " + e.getMessage());
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

    /**
     * Start a lightweight HTTP health check endpoint.
     * Exposes four paths:
     * <ul>
     *   <li>{@code /health} — liveness probe (always 200 if agent is running)</li>
     *   <li>{@code /ready} — readiness probe (200 once agent has interceptors)</li>
     *   <li>{@code /stats} — live instrumentation counters: classes transformed and
     *       interceptors actually <em>fired</em> at runtime (vs. merely registered).
     *       This is the positive signal that instrumentation is taking effect —
     *       {@code interceptorInvocationCount > 0} means at least one instrumented
     *       method has executed. Essential for APM/IAST operators and for tests that
     *       must verify interception survives a packaging/classloader change.</li>
     *   <li>{@code /agent/interception} — runtime toggle for the global kill-switch</li>
     * </ul>
     *
     * @param port the port to bind
     */
    private static void startHealthEndpoint(int port) throws Exception {
        healthServer = HttpServer.create(new InetSocketAddress(port), 0);

        healthServer.createContext("/health", exchange -> {
            try {
                long uptimeSec = (System.currentTimeMillis() - startTimeMs) / 1000;
                String json = "{\"status\":\"UP\",\"agent\":\"weaver-girl\",\"uptimeSeconds\":" + uptimeSec + "}";
                byte[] bytes = json.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, 0);
                exchange.getResponseBody().close();
            }
        });

        healthServer.createContext("/ready", exchange -> {
            try {
                WeaverGirl wg = weaverGirlInstance;
                int defCount = (wg != null) ? wg.getRegistry().getAllDefinitions().size() : 0;
                if (defCount > 0) {
                    String json = "{\"status\":\"READY\",\"interceptorCount\":" + defCount + "}";
                    byte[] bytes = json.getBytes("UTF-8");
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } else {
                    String json = "{\"status\":\"NOT_READY\",\"interceptorCount\":0}";
                    byte[] bytes = json.getBytes("UTF-8");
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(503, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
                exchange.getResponseBody().close();
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, 0);
                exchange.getResponseBody().close();
            }
        });

        // Runtime toggle for the global interception kill-switch.
        // POST /agent/interception  {"enabled":true|false}  -> flips the switch live.
        healthServer.createContext("/agent/interception", exchange -> {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[256];
                int n;
                java.io.InputStream is = exchange.getRequestBody();
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
                String body = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                boolean desired = body.contains("\"enabled\":true")
                        || body.contains("\"enabled\": true");
                com.github.cc11001100.weavergirl.core.InterceptorHolder
                        .setInterceptionEnabled(desired, "rest:/agent/interception");
                String json = "{\"enabled\":" + desired + "}";
                byte[] bytes = json.getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, 0);
                exchange.getResponseBody().close();
            }
        });

        // Live instrumentation counters. interceptorInvocationCount is incremented by
        // the inlined advice on EVERY instrumented method execution, so a non-zero value
        // is positive proof interception is wired end-to-end (advice -> registry -> plugin).
        // The smoke harness asserts this is non-zero after driving instrumented traffic,
        // which catches the silent "null registry" failure mode of a classloader split.
        healthServer.createContext("/stats", exchange -> {
            try {
                AgentStatus status = AgentStatus.getInstance();
                long uptimeSec = (System.currentTimeMillis() - startTimeMs) / 1000;
                // Prefer the live registry count (kept accurate as plugins register) over
                // the cached AgentStatus value, which is only set on certain code paths.
                WeaverGirl wg = weaverGirlInstance;
                int registered = (wg != null) ? wg.getRegistry().getAllDefinitions().size()
                        : status.getRegisteredInterceptorCount();
                StringBuilder sb = new StringBuilder(256);
                sb.append("{\"status\":\"UP\",\"uptimeSeconds\":").append(uptimeSec)
                  .append(",\"transformationCount\":").append(status.getTransformationCount())
                  .append(",\"transformationErrorCount\":").append(status.getTransformationErrorCount())
                  .append(",\"interceptorInvocationCount\":").append(status.getInterceptorInvocationCount())
                  .append(",\"interceptorErrorCount\":").append(status.getInterceptorErrorCount())
                  .append(",\"registeredInterceptorCount\":").append(registered)
                  .append(",\"activePluginCount\":").append(status.getActivePluginCount())
                  .append("}");
                byte[] bytes = sb.toString().getBytes("UTF-8");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, 0);
                exchange.getResponseBody().close();
            }
        });

        healthServer.setExecutor(null);
        healthServer.start();
    }
}
