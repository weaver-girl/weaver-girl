package com.github.cc11001100.weavergirl.api.config;

/**
 * Holder for the DynamicConfigManager singleton.
 *
 * <p>Uses the initialize-on-demand holder idiom for thread-safe lazy initialization.
 * The core module provides the actual implementation via
 * {@link #setInstance(DynamicConfigManager)}.</p>
 *
 * @since 1.1.0
 */
public final class DynamicConfigManagerHolder {

    private static volatile DynamicConfigManager instance;

    private DynamicConfigManagerHolder() {
    }

    /**
     * Get the singleton instance. Returns null if not yet initialized.
     *
     * @return the DynamicConfigManager instance, or null
     */
    public static DynamicConfigManager getInstance() {
        return instance;
    }

    /**
     * Set the singleton instance. Called by the core module during agent bootstrap.
     *
     * @param manager the implementation to use
     */
    public static void setInstance(DynamicConfigManager manager) {
        instance = manager;
    }
}
