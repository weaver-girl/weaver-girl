package com.github.cc11001100.weavergirl.core.plugin;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Child-first (parent-last) ClassLoader for plugin isolation.
 * Each plugin JAR gets its own PluginClassLoader instance.
 *
 * <p>Loading order:</p>
 * <ol>
 *   <li>Check if the class is a JDK/Java core class — delegate to parent</li>
 *   <li>Try to load from the plugin JAR first (child-first)</li>
 *   <li>Fall back to parent ClassLoader if not found</li>
 * </ol>
 *
 * <p>This prevents plugin-to-plugin coupling and supports the
 * "drop-in JAR" deployment model. Plugins cannot see each other's
 * classes but can see agent-core classes (via parent delegation).</p>
 */
public class PluginClassLoader extends URLClassLoader {

    private static final String JAVA_PREFIX = "java.";
    private static final String JAVAX_PREFIX = "javax.";
    private static final String SUN_PREFIX = "sun.";

    public PluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // 1. JDK classes always delegate to parent
        if (name.startsWith(JAVA_PREFIX) || name.startsWith(JAVAX_PREFIX) || name.startsWith(SUN_PREFIX)) {
            return super.loadClass(name, resolve);
        }

        // 2. Check if already loaded
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
            return loaded;
        }

        // 3. Try child-first (load from plugin JAR)
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
