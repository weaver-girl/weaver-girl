package com.github.cc11001100.weavergirl.api.plugin;

import java.util.*;

/**
 * Health status of a plugin.
 *
 * @since 1.2.0
 */
public class PluginHealth {

    public enum Status { HEALTHY, DEGRADED, UNHEALTHY }

    private final String pluginName;
    private final Status status;
    private final String message;
    private final long checkTimestamp;

    public PluginHealth(String pluginName, Status status, String message) {
        this.pluginName = pluginName;
        this.status = status;
        this.message = message;
        this.checkTimestamp = System.currentTimeMillis();
    }

    public String getPluginName() { return pluginName; }
    public Status getStatus() { return status; }
    public String getMessage() { return message; }
    public long getCheckTimestamp() { return checkTimestamp; }

    public boolean isHealthy() { return status == Status.HEALTHY; }

    @Override
    public String toString() {
        return "PluginHealth{" + pluginName + ": " + status + (message != null ? " - " + message : "") + "}";
    }

    public static PluginHealth healthy(String name) {
        return new PluginHealth(name, Status.HEALTHY, null);
    }

    public static PluginHealth degraded(String name, String message) {
        return new PluginHealth(name, Status.DEGRADED, message);
    }

    public static PluginHealth unhealthy(String name, String message) {
        return new PluginHealth(name, Status.UNHEALTHY, message);
    }
}
