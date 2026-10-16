package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves plugin loading order based on declared dependencies. Uses topological sort to ensure
 * dependencies are loaded before dependents.
 *
 * <p>If a circular dependency is detected, an error is logged and the plugins involved are loaded
 * in their original order (best-effort).
 */
public class PluginDependencyResolver {

  private static final Logger log = LoggerFactory.getLogger(PluginDependencyResolver.class);

  /**
   * Plugins left out of the last {@link #resolve} because a plugin they {@link WeaverPlugin#requires()
   * require} was not loaded, keyed by the plugin and giving the missing name. A plugin that only {@link
   * WeaverPlugin#depends() depends} on something missing is not here: it still loads.
   */
  private Map<WeaverPlugin, String> unsatisfied = new HashMap<>();

  /** The plugins the last {@link #resolve} left out, and the required plugin each one was missing. */
  public Map<WeaverPlugin, String> unsatisfied() {
    return Collections.unmodifiableMap(unsatisfied);
  }

  /** What {@code plugin} requires, from its method and from the jar metadata, without duplicates. */
  private static List<String> requirementsOf(
      WeaverPlugin plugin, Map<WeaverPlugin, List<String>> declared) {
    List<String> requirements = new ArrayList<>();
    for (String dep : plugin.requires()) {
      if (!requirements.contains(dep)) {
        requirements.add(dep);
      }
    }
    List<String> fromMetadata = declared.get(plugin);
    if (fromMetadata != null) {
      for (String dep : fromMetadata) {
        if (!requirements.contains(dep)) {
          requirements.add(dep);
        }
      }
    }
    return requirements;
  }

  /** Whether every plugin of this name was left out, so the name no longer satisfies a requirement. */
  private boolean unsatisfied(List<WeaverPlugin> named) {
    for (WeaverPlugin plugin : named) {
      if (!unsatisfied.containsKey(plugin)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Sort plugins by their declared dependencies using topological sort.
   *
   * @param plugins the plugins to sort
   * @return a new list with plugins in dependency-resolved order
   */
  public List<WeaverPlugin> resolve(List<WeaverPlugin> plugins) {
    return resolve(plugins, java.util.Collections.emptyMap());
  }

  /**
   * {@code declared} carries hard requirements that come from a plugin's jar metadata rather than its {@link
   * WeaverPlugin#requires()} method, so a plugin described by a properties file is held to them too. A plugin
   * absent from the map declares nothing that way.
   */
  public List<WeaverPlugin> resolve(
      List<WeaverPlugin> plugins, Map<WeaverPlugin, List<String>> declared) {
    // Index by name, but keep every plugin. Two plugins may share a name — a drop-in shipped to
    // replace a built-in, or two jars that both picked a careless one — and a map would keep only
    // the last, so the earlier one would vanish from the result and never load. Dependencies are
    // declared by name, so every plugin of that name satisfies them.
    Map<String, Set<String>> dependencies = new HashMap<>();
    Map<String, List<WeaverPlugin>> byName = new HashMap<>();

    for (WeaverPlugin plugin : plugins) {
      String name = plugin.name();
      byName.computeIfAbsent(name, k -> new ArrayList<>()).add(plugin);
      Set<String> deps = new HashSet<>();
      // depends() is an array a plugin is free to return null from; that declares nothing, not a crash.
      String[] declaredDependencies = plugin.depends();
      if (declaredDependencies != null) {
        for (String dep : declaredDependencies) {
          deps.add(dep);
        }
      }
      // A hard requirement orders initialization too, so it joins the same set. It is recorded separately
      // below because a missing one keeps the plugin out, whereas a missing dependency only warns.
      for (String dep : requirementsOf(plugin, declared)) {
        deps.add(dep);
      }
      dependencies.put(name, deps);
    }

    // A plugin that requires something absent cannot run, and neither can a plugin that requires it. This
    // repeats until nothing more drops out, so a chain of requirements collapses from the missing end.
    unsatisfied = new HashMap<>();
    boolean dropped = true;
    while (dropped) {
      dropped = false;
      for (WeaverPlugin plugin : new ArrayList<>(plugins)) {
        if (unsatisfied.containsKey(plugin)) {
          continue;
        }
        for (String dep : requirementsOf(plugin, declared)) {
          if (!byName.containsKey(dep) || unsatisfied(byName.get(dep))) {
            unsatisfied.put(plugin, dep);
            log.warn(
                "Plugin '{}' requires '{}' which is not available — not loading it", plugin.name(), dep);
            dropped = true;
            break;
          }
        }
      }
    }

    // Nothing left to order. A single plugin still passes through the requirement check above, so one that
    // requires something absent is dropped even though there is no ordering to do.
    java.util.List<WeaverPlugin> remaining = new ArrayList<>();
    for (WeaverPlugin plugin : plugins) {
      if (!unsatisfied.containsKey(plugin)) {
        remaining.add(plugin);
      }
    }
    if (remaining.size() <= 1) {
      return remaining;
    }

    // Topological sort (DFS-based)
    List<WeaverPlugin> result = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    Set<String> visiting = new HashSet<>();

    for (WeaverPlugin plugin : plugins) {
      if (!visited.contains(plugin.name()) && !unsatisfied.containsKey(plugin)) {
        visit(plugin.name(), byName, dependencies, visited, visiting, result);
      }
    }

    return result;
  }

  private void visit(
      String name,
      Map<String, List<WeaverPlugin>> byName,
      Map<String, Set<String>> dependencies,
      Set<String> visited,
      Set<String> visiting,
      List<WeaverPlugin> result) {
    if (visited.contains(name)) {
      return;
    }
    if (visiting.contains(name)) {
      log.warn("Circular dependency detected involving plugin: {}", name);
      return; // Break cycle
    }

    visiting.add(name);

    Set<String> deps = dependencies.getOrDefault(name, Collections.emptySet());
    for (String dep : deps) {
      if (!byName.containsKey(dep)) {
        log.warn("Plugin '{}' depends on '{}' which is not loaded", name, dep);
        continue;
      }
      visit(dep, byName, dependencies, visited, visiting, result);
    }

    visiting.remove(name);
    visited.add(name);

    List<WeaverPlugin> named = byName.get(name);
    if (named != null) {
      named.removeAll(unsatisfied.keySet());
      if (named.size() > 1) {
        log.warn(
            "Multiple plugins are named '{}'; all of them load, but a dependency on this name is"
                + " satisfied by each of them",
            name);
      }
      result.addAll(named);
    }
  }
}
