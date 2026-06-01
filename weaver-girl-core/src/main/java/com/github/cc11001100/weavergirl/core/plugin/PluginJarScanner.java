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
                URL jarUrl = jar.toURI().toURL();
                PluginClassLoader cl = new PluginClassLoader(new URL[]{jarUrl}, parentClassLoader);
                classLoaders.add(cl);
                log.info("Loaded plugin JAR: {} (ClassLoader: {})", jar.getName(), cl);
            } catch (Exception e) {
                log.error("Failed to load plugin JAR {}: {}", jar.getName(), e.getMessage());
            }
        }

        return classLoaders;
    }
}
