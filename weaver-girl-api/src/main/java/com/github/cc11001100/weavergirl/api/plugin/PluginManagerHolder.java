package com.github.cc11001100.weavergirl.api.plugin;

/**
 * Holder for the PluginManager singleton.
 *
 * @since 1.1.0
 */
public final class PluginManagerHolder {

    private static volatile PluginManager instance;

    private PluginManagerHolder() {
    }

    public static PluginManager getInstance() {
        return instance;
    }

    public static void setInstance(PluginManager manager) {
        instance = manager;
    }
}
