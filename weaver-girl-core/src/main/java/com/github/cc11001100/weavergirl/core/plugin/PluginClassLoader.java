package com.github.cc11001100.weavergirl.core.plugin;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Child-first (parent-last) ClassLoader for plugin isolation. Each plugin JAR gets its own
 * PluginClassLoader instance.
 *
 * <p>Loading order:
 *
 * <ol>
 *   <li>Check if the class belongs to a parent-first package (agent API, JDK) — delegate to parent
 *   <li>Try to load from the plugin JAR first (child-first)
 *   <li>Fall back to parent ClassLoader if not found
 * </ol>
 *
 * <p>This prevents plugin-to-plugin coupling and supports the "drop-in JAR" deployment model.
 * Plugins cannot see each other's classes but can see agent-core classes (via parent delegation).
 *
 * <p>Security: Agent API and JDK packages are always loaded parent-first to prevent a malicious
 * plugin from spoofing agent framework classes.
 */
public class PluginClassLoader extends URLClassLoader {

  /**
   * Packages that must always be loaded by the parent (agent) ClassLoader. This prevents plugins
   * from overriding agent API classes or JDK classes (class spoofing attack).
   */
  private static final Set<String> PARENT_FIRST_PACKAGES;

  static {
    Set<String> set = new HashSet<>();
    set.add("com.github.cc11001100.weavergirl.api");
    set.add("com.github.cc11001100.weavergirl.annotation");
    set.add("java.");
    set.add("javax.");
    set.add("sun.");
    set.add("jdk.");
    PARENT_FIRST_PACKAGES = Collections.unmodifiableSet(set);
  }

  public PluginClassLoader(URL[] urls, ClassLoader parent) {
    super(urls, parent);
  }

  /**
   * What another plugin exports, loaded by that plugin's class loader. A plugin that depends on the exporter
   * can see exactly these classes and no others the exporter defines, so one plugin can publish a service
   * interface another consumes without it having to live in the shared API.
   *
   * <p>An entry is either a package or one class. A package exposes every class directly in it and nothing in
   * a subpackage; a class exposes that one class, so an implementation that sits next to an exported interface
   * stays hidden.
   */
  private final java.util.concurrent.ConcurrentHashMap<String, PluginClassLoader> exportedPackages =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final java.util.concurrent.ConcurrentHashMap<String, PluginClassLoader> exportedClasses =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * See {@code name} through {@code exporter}. {@code className} says which it is, because a package and a class
   * are written the same way: {@code com.example.clock} is the package, {@code com.example.clock.Clock} is the
   * one class. A package is matched exactly, so a class in a subpackage the exporter did not name stays hidden,
   * and a class export exposes that one class and not the one sitting next to it.
   */
  public void addExport(String name, PluginClassLoader exporter, boolean className) {
    if (name == null || exporter == null || exporter == this) {
      return;
    }
    (className ? exportedClasses : exportedPackages).put(name, exporter);
  }

  /**
   * The class loader a plugin exported {@code className} through, or {@code null}. A class named on its own
   * wins over the package it sits in, so a plugin can export one class of a package and keep the rest.
   */
  private PluginClassLoader exporterOf(String className) {
    PluginClassLoader named = exportedClasses.get(className);
    if (named != null) {
      return named;
    }
    int dot = className.lastIndexOf('.');
    if (dot < 0) {
      return null;
    }
    return exportedPackages.get(className.substring(0, dot));
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    // Java 8 compatible: use synchronized(this) instead of getClassLoadingLock()
    // which was added in Java 9
    synchronized (this) {
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

      // 3. A package another plugin exported. It is asked before this jar, so a plugin cannot shadow a
      // class it consumes by shipping one of the same name.
      PluginClassLoader exporter = exporterOf(name);
      if (exporter != null) {
        Class<?> exported = exporter.loadClass(name);
        if (resolve) {
          resolveClass(exported);
        }
        return exported;
      }

      // 4. Try child-first (load from plugin JAR and its dependencies)
      try {
        Class<?> clazz = findClass(name);
        if (resolve) {
          resolveClass(clazz);
        }
        return clazz;
      } catch (ClassNotFoundException e) {
        // 5. Fall back to parent
        return super.loadClass(name, resolve);
      }
    }
  }
}
