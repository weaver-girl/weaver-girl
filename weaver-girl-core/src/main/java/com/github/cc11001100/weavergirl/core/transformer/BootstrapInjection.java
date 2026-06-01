// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/transformer/BootstrapInjection.java
package com.github.cc11001100.weavergirl.core.transformer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * Injects agent helper classes into the Bootstrap ClassLoader.
 *
 * <p>To intercept classes loaded by the Bootstrap ClassLoader (e.g., java.net.URL,
 * javax.servlet.http.HttpServlet), the agent's advice classes and their dependencies
 * must be visible to the Bootstrap ClassLoader. This class appends the agent JAR
 * (or a dedicated helper JAR) to the bootstrap class search path.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   BootstrapInjection injection = new BootstrapInjection();
 *   injection.inject(instrumentation);
 * </pre>
 */
public class BootstrapInjection {

    private static final Logger log = LoggerFactory.getLogger(BootstrapInjection.class);

    private boolean injected = false;

    /**
     * Inject the agent JAR into the Bootstrap ClassLoader's search path.
     * This allows the advice classes (InterceptAdvice, InterceptorHolder, etc.)
     * to be visible when instrumenting bootstrap classes.
     *
     * @param instrumentation the JVM Instrumentation instance
     */
    public void inject(Instrumentation instrumentation) {
        if (injected) {
            return;
        }

        try {
            // Find the agent JAR that contains InterceptAdvice
            String agentJarPath = findAgentJarPath();
            if (agentJarPath == null) {
                log.warn("Could not locate agent JAR for bootstrap injection");
                return;
            }

            JarFile jarFile = new JarFile(new File(agentJarPath));
            instrumentation.appendToBootstrapClassLoaderSearch(jarFile);
            injected = true;
            log.info("Injected agent JAR into Bootstrap ClassLoader: {}", agentJarPath);
        } catch (IOException e) {
            log.error("Failed to inject into Bootstrap ClassLoader: {}", e.getMessage());
        }
    }

    /**
     * Find the path to the agent JAR by locating the JAR that contains
     * the InterceptAdvice class.
     */
    private String findAgentJarPath() {
        try {
            // The InterceptAdvice class is in the agent's core module
            String className = "com.github.cc11001100.weavergirl.core.InterceptAdvice";
            String resourcePath = className.replace('.', '/') + ".class";
            java.net.URL resource = getClass().getClassLoader().getResource(resourcePath);
            if (resource != null) {
                String protocol = resource.getProtocol();
                if ("jar".equals(protocol)) {
                    // Extract JAR path from jar:file:/path/to/jar!/entry format
                    String jarUrl = resource.toString();
                    int bangIndex = jarUrl.indexOf('!');
                    if (bangIndex > 0) {
                        String filePath = jarUrl.substring("jar:".length(), bangIndex);
                        if (filePath.startsWith("file:")) {
                            return filePath.substring("file:".length());
                        }
                        return filePath;
                    }
                } else if ("file".equals(protocol)) {
                    // Running from IDE/classpath (not from JAR)
                    log.info("Running from classpath (not JAR), bootstrap injection not needed for tests");
                    return null;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to find agent JAR path: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Check if bootstrap injection has been performed.
     */
    public boolean isInjected() {
        return injected;
    }
}
