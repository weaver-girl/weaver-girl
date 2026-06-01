// weaver-girl-agent/src/main/java/com/github/cc11001100/weavergirl/agent/WeaverGirlAgent.java
package com.github.cc11001100.weavergirl.agent;

import com.github.cc11001100.weavergirl.core.WeaverGirl;
import com.github.cc11001100.weavergirl.core.config.YamlConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;

/**
 * Java Agent entry point.
 * Supports both premain (startup-time) and agentmain (runtime attach) modes.
 *
 * <p>Usage:</p>
 * <pre>
 *   java -javaagent:weaver-girl-agent.jar -jar app.jar
 *   java -javaagent:weaver-girl-agent.jar=config=/path/to/weaver.yml -jar app.jar
 * </pre>
 */
public class WeaverGirlAgent {

    private static final Logger log = LoggerFactory.getLogger(WeaverGirlAgent.class);
    private static final String CONFIG_PREFIX = "config=";

    /**
     * Premain entry — called before application main() when using -javaagent flag.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        init(agentArgs, instrumentation);
    }

    /**
     * Agentmain entry — called when dynamically attaching to a running JVM.
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        init(agentArgs, instrumentation);
    }

    private static void init(String agentArgs, Instrumentation instrumentation) {
        log.info("WeaverGirl agent initializing...");

        WeaverGirl weaverGirl = WeaverGirl.bootstrap(instrumentation);

        // Load YAML config if specified via agent arguments
        if (agentArgs != null && !agentArgs.isEmpty()) {
            String configPath = parseConfigPath(agentArgs);
            if (configPath != null) {
                YamlConfigLoader configLoader = new YamlConfigLoader();
                configLoader.loadFromFile(configPath, weaverGirl.getRegistry());
            }
        }

        log.info("WeaverGirl agent initialized with {} interceptor definitions",
                weaverGirl.getRegistry().getAllDefinitions().size());
    }

    private static String parseConfigPath(String agentArgs) {
        if (agentArgs.startsWith(CONFIG_PREFIX)) {
            return agentArgs.substring(CONFIG_PREFIX.length()).trim();
        }
        if (!agentArgs.isEmpty() && (agentArgs.endsWith(".yml") || agentArgs.endsWith(".yaml"))) {
            return agentArgs;
        }
        return null;
    }
}
