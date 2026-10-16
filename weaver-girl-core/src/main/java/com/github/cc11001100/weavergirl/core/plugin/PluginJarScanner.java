package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.plugin.PluginMetadata;
import java.io.File;
import java.io.FileFilter;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans a directory for plugin JARs and creates isolated ClassLoaders for each. Each JAR file gets
 * its own PluginClassLoader with child-first delegation.
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
    return scan(pluginDir, parentClassLoader, null, null);
  }

  /**
   * Scan the directory and return a class loader per selected jar, applying the capability filter.
   *
   * @param disabledPlugins comma-separated capability names to leave out
   * @param enabledPlugins comma-separated capability names to load exclusively; empty loads all
   */
  public List<PluginClassLoader> scan(
      String pluginDir,
      ClassLoader parentClassLoader,
      String disabledPlugins,
      String enabledPlugins) {
    List<PluginClassLoader> classLoaders = new ArrayList<>();
    for (PluginJar jar : scanJars(pluginDir, parentClassLoader, disabledPlugins, enabledPlugins)) {
      classLoaders.add(jar.classLoader);
    }
    return classLoaders;
  }

  /**
   * A plugin jar together with the class loader that reads it and the metadata it declared.
   *
   * <p>{@code metadata} is null when the jar has no {@code META-INF/weaver-girl-plugin.properties},
   * which is the case for the built-in capability jars. A third-party jar uses it to say which plugin
   * contract it was built against.
   */
  public static final class PluginJar {
    final File file;
    final PluginClassLoader classLoader;
    final PluginMetadata metadata;

    PluginJar(File file, PluginClassLoader classLoader, PluginMetadata metadata) {
      this.file = file;
      this.classLoader = classLoader;
      this.metadata = metadata;
    }
  }

  /**
   * Scan the given directory for JAR files and create a PluginClassLoader only for the capabilities
   * the configuration enables. Jars for disabled capabilities are never opened, so their classes are
   * never loaded.
   *
   * @param pluginDir the directory to scan
   * @param parentClassLoader the parent ClassLoader (agent core CL)
   * @param disabledPlugins comma-separated capability names to leave out (see {@link
   *     PluginJarSelector})
   * @param enabledPlugins comma-separated capability names to load exclusively; empty loads all
   * @return list of PluginClassLoader instances, one per selected JAR
   */
  public List<PluginJar> scanJars(
      String pluginDir,
      ClassLoader parentClassLoader,
      String disabledPlugins,
      String enabledPlugins) {
    File dir = new File(pluginDir);
    if (!dir.exists() || !dir.isDirectory()) {
      log.info("Plugin directory does not exist: {}", pluginDir);
      return Collections.emptyList();
    }

    File[] found =
        dir.listFiles((FileFilter) file -> file.isFile() && file.getName().endsWith(".jar"));

    if (found == null || found.length == 0) {
      log.info("No plugin JARs found in: {}", pluginDir);
      return Collections.emptyList();
    }

    List<File> jars = PluginJarSelector.select(
        java.util.Arrays.asList(found), disabledPlugins, enabledPlugins);
    if (jars.isEmpty()) {
      log.info("No plugin JARs selected in: {} ({} present)", pluginDir, found.length);
      return Collections.emptyList();
    }

    List<PluginJar> classLoaders = new ArrayList<>();
    for (File jar : jars) {
      try {
        List<URL> urls = new ArrayList<>();
        urls.add(jar.toURI().toURL());

        // Dependency jars. lib/<plugin>/ is private to this plugin; lib/ itself holds jars every
        // plugin may use. A private jar wins when both directories carry the same file name, so one
        // plugin can pin a version without forcing it on the others.
        addDependencyJars(urls, new File(new File(jar.getParent(), "lib"), pluginLibName(jar)));
        addDependencyJars(urls, new File(jar.getParent(), "lib"));

        PluginClassLoader cl = new PluginClassLoader(urls.toArray(new URL[0]), parentClassLoader);
        classLoaders.add(new PluginJar(jar, cl, readMetadata(jar)));
        log.info(
            "Loaded plugin JAR: {} with {} URLs (ClassLoader: {})", jar.getName(), urls.size(), cl);
      } catch (Exception e) {
        log.error("Failed to load plugin JAR {}: {}", jar.getName(), e.getMessage());
      }
    }

    return classLoaders;
  }

  /**
   * The directory under {@code lib/} that holds jars private to this plugin: the jar name without
   * its {@code .jar} suffix and without a trailing {@code -<version>}.
   */
  static String pluginLibName(File jar) {
    String name = jar.getName();
    if (name.endsWith(".jar")) {
      name = name.substring(0, name.length() - 4);
    }
    return name.replaceFirst("-\\d.*$", "");
  }

  /** Append the jars in {@code dir} that are not already on the classpath. */
  private static void addDependencyJars(List<URL> urls, File dir) throws java.net.MalformedURLException {
    if (!dir.isDirectory()) {
      return;
    }
    File[] jars = dir.listFiles((directory, name) -> name.endsWith(".jar"));
    if (jars == null) {
      return;
    }
    Set<String> present = new HashSet<>();
    for (URL url : urls) {
      present.add(new File(url.getPath()).getName());
    }
    for (File jar : jars) {
      if (present.add(jar.getName())) {
        urls.add(jar.toURI().toURL());
      }
    }
  }

  /** Read {@code META-INF/weaver-girl-plugin.properties} from a jar, or null when it has none. */
  private static PluginMetadata readMetadata(File jar) {
    JarFile jarFile = null;
    try {
      jarFile = new JarFile(jar);
      JarEntry entry = jarFile.getJarEntry(PluginMetadata.METADATA_PATH);
      if (entry == null) {
        return null;
      }
      InputStream in = jarFile.getInputStream(entry);
      try {
        return PluginMetadata.fromProperties(in);
      } finally {
        in.close();
      }
    } catch (Exception e) {
      log.warn("Could not read plugin metadata from {}: {}", jar.getName(), e.getMessage());
      return null;
    } finally {
      if (jarFile != null) {
        try {
          jarFile.close();
        } catch (Exception ignored) {
          // best effort
        }
      }
    }
  }
}
