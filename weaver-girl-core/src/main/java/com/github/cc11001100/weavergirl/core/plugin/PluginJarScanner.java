package com.github.cc11001100.weavergirl.core.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Scans a directory for plugin JARs and creates isolated ClassLoaders for each.
 * Each JAR file gets its own PluginClassLoader with child-first delegation.
 */
public class PluginJarScanner {

    private static final Logger log = LoggerFactory.getLogger(PluginJarScanner.class);

    /**
     * Scan the given directory for JAR files and create a PluginClassLoader for each.
     *
     * @param pluginDir the directory to scan
     * @param parentClassLoader the parent ClassLoader (agent core CL)
     * @return list of PluginClassLoader instances, one per JAR
     */
    public List<PluginClassLoader> scan(String pluginDir, ClassLoader parentClassLoader) {
        File dir = new File(pluginDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.info("Plugin directory does not exist: {}", pluginDir);
            return Collections.emptyList();
        }

        File[] jars = dir.listFiles((FileFilter) file ->
                file.isFile() && file.getName().endsWith(".jar"));

        if (jars == null || jars.length == 0) {
            log.info("No plugin JARs found in: {}", pluginDir);
            return Collections.emptyList();
        }

        List<PluginClassLoader> classLoaders = new ArrayList<>();
        for (File jar : jars) {
            try {
                List<URL> urls = new ArrayList<>();
                urls.add(jar.toURI().toURL());

                // Scan lib/ subdirectory next to the plugin JAR for dependency JARs
                File libDir = new File(jar.getParent(), "lib");
                if (libDir.isDirectory()) {
                    File[] libJars = libDir.listFiles((libDirectory, name) -> name.endsWith(".jar"));
                    if (libJars != null) {
                        for (File libJar : libJars) {
                            urls.add(libJar.toURI().toURL());
                        }
                    }
                }

                PluginClassLoader cl = new PluginClassLoader(urls.toArray(new URL[0]), parentClassLoader);
                classLoaders.add(cl);
                log.info("Loaded plugin JAR: {} with {} URLs (ClassLoader: {})", jar.getName(), urls.size(), cl);
            } catch (Exception e) {
                log.error("Failed to load plugin JAR {}: {}", jar.getName(), e.getMessage());
            }
        }

        return classLoaders;
    }
}
