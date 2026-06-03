package com.github.cc11001100.weavergirl.api.exporter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry for managing data exporters.
 *
 * @since 1.2.0
 */
public final class ExporterRegistry {

    private static final ConcurrentHashMap<String, DataExporter> exporters = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<String> activeExporters = new CopyOnWriteArrayList<>();

    private ExporterRegistry() {
    }

    /**
     * Register a data exporter.
     *
     * @param exporter the exporter to register
     */
    public static void register(DataExporter exporter) {
        if (exporter != null && exporter.name() != null) {
            exporters.put(exporter.name(), exporter);
        }
    }

    /**
     * Unregister a data exporter by name.
     *
     * @param name the exporter name
     */
    public static void unregister(String name) {
        if (name != null) {
            exporters.remove(name);
            activeExporters.remove(name);
        }
    }

    /**
     * Activate an exporter so it receives events.
     *
     * @param name the exporter name
     * @return true if activated successfully
     */
    public static boolean activate(String name) {
        if (name != null && exporters.containsKey(name)) {
            if (!activeExporters.contains(name)) {
                activeExporters.add(name);
            }
            return true;
        }
        return false;
    }

    /**
     * Deactivate an exporter.
     *
     * @param name the exporter name
     */
    public static void deactivate(String name) {
        if (name != null) {
            activeExporters.remove(name);
        }
    }

    /**
     * Export an event to all active exporters.
     *
     * @param event the event to export
     */
    public static void exportEvent(com.github.cc11001100.weavergirl.api.event.InterceptorEvent event) {
        if (event == null) return;
        for (String name : activeExporters) {
            DataExporter exporter = exporters.get(name);
            if (exporter != null) {
                try {
                    exporter.export(event);
                } catch (Exception e) {
                    // Log but don't propagate — exporters must not break interception
                }
            }
        }
    }

    /**
     * Get a specific exporter by name.
     */
    public static DataExporter get(String name) {
        return name != null ? exporters.get(name) : null;
    }

    /**
     * Get all registered exporter names.
     */
    public static Set<String> getExporterNames() {
        return Collections.unmodifiableSet(exporters.keySet());
    }

    /**
     * Get active exporter names.
     */
    public static List<String> getActiveExporterNames() {
        return Collections.unmodifiableList(new ArrayList<>(activeExporters));
    }

    /**
     * Initialize an exporter with configuration.
     */
    public static void initExporter(String name, Map<String, String> config) {
        DataExporter exporter = exporters.get(name);
        if (exporter != null && config != null) {
            exporter.init(config);
        }
    }

    /**
     * Flush all active exporters.
     */
    public static void flushAll() {
        for (String name : activeExporters) {
            DataExporter exporter = exporters.get(name);
            if (exporter != null) {
                try {
                    exporter.flush();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Shut down all registered exporters.
     */
    public static void shutdownAll() {
        for (DataExporter exporter : exporters.values()) {
            try {
                exporter.shutdown();
            } catch (Exception ignored) {
            }
        }
        exporters.clear();
        activeExporters.clear();
    }

    /**
     * Get health status of all active exporters.
     */
    public static Map<String, Boolean> getHealthStatus() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        for (String name : activeExporters) {
            DataExporter exporter = exporters.get(name);
            status.put(name, exporter != null && exporter.isHealthy());
        }
        return status;
    }
}
