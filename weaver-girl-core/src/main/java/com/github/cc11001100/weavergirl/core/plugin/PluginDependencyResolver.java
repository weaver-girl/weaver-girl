package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Resolves plugin loading order based on declared dependencies.
 * Uses topological sort to ensure dependencies are loaded before dependents.
 *
 * <p>If a circular dependency is detected, an error is logged and the
 * plugins involved are loaded in their original order (best-effort).</p>
 */
public class PluginDependencyResolver {

    private static final Logger log = LoggerFactory.getLogger(PluginDependencyResolver.class);

    /**
     * Sort plugins by their declared dependencies using topological sort.
     *
     * @param plugins the plugins to sort
     * @return a new list with plugins in dependency-resolved order
     */
    public List<WeaverPlugin> resolve(List<WeaverPlugin> plugins) {
        if (plugins.size() <= 1) {
            return new ArrayList<>(plugins);
        }

        // Build adjacency list: plugin name -> set of dependency names
        Map<String, Set<String>> dependencies = new HashMap<>();
        Map<String, WeaverPlugin> byName = new HashMap<>();

        for (WeaverPlugin plugin : plugins) {
            String name = plugin.name();
            byName.put(name, plugin);
            Set<String> deps = new HashSet<>();
            for (String dep : plugin.depends()) {
                deps.add(dep);
            }
            dependencies.put(name, deps);
        }

        // Topological sort (DFS-based)
        List<WeaverPlugin> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (WeaverPlugin plugin : plugins) {
            if (!visited.contains(plugin.name())) {
                visit(plugin.name(), byName, dependencies, visited, visiting, result);
            }
        }

        return result;
    }

    private void visit(String name, Map<String, WeaverPlugin> byName,
                       Map<String, Set<String>> dependencies,
                       Set<String> visited, Set<String> visiting,
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

        WeaverPlugin plugin = byName.get(name);
        if (plugin != null) {
            result.add(plugin);
        }
    }
}
