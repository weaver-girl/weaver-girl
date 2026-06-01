package com.github.cc11001100.weavergirl.core.plugin;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;

/**
 * Child-first (parent-last) ClassLoader for plugin isolation.
 * Each plugin JAR gets its own PluginClassLoader instance.
 *
 * <p>Loading order:</p>
 * <ol>
 *   <li>Check if the class belongs to a parent-first package (agent API, JDK) — delegate to parent</li>
 *   <li>Try to load from the plugin JAR first (child-first)</li>
 *   <li>Fall back to parent ClassLoader if not found</li>
 * </ol>
 *
 * <p>This prevents plugin-to-plugin coupling and supports the
 * "drop-in JAR" deployment model. Plugins cannot see each other's
 * classes but can see agent-core classes (via parent delegation).</p>
 *
 * <p>Security: Agent API and JDK packages are always loaded parent-first
 * to prevent a malicious plugin from spoofing agent framework classes.</p>
 */
public class PluginClassLoader extends URLClassLoader {

    /**
     * Packages that must always be loaded by the parent (agent) ClassLoader.
     * This prevents plugins from overriding agent API classes or JDK classes
     * (class spoofing attack).
     */
    private static final Set<String> PARENT_FIRST_PACKAGES = Set.of(
        "com.github.cc11001100.weavergirl.api",
        "com.github.cc11001100.weavergirl.annotation",
        "java.", "javax.", "sun.", "jdk."
    );

    public PluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 1. Parent-first for agent API and JDK classes
            for (String prefix : PARENT_FIRST_PACKAGES) {
                if (name.startsWith(prefix)) {
                    return super.loadClass(name, resolve);
                }
            }

            // 2. Check if already loaded
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }

            // 3. Try child-first (load from plugin JAR and its dependencies)
            try {
                Class<?> clazz = findClass(name);
                if (resolve) {
                    resolveClass(clazz);
                }
                return clazz;
            } catch (ClassNotFoundException e) {
                // 4. Fall back to parent
                return super.loadClass(name, resolve);
            }
        }
    }
}
