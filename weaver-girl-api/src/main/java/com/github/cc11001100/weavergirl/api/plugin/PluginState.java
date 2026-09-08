package com.github.cc11001100.weavergirl.api.plugin;

/**
 * Represents the lifecycle state of a plugin.
 *
 * <p>State transitions:
 *
 * <pre>
 *   LOADED → ACTIVE → DISABLED → ACTIVE (re-enable)
 *                  ↘ UNLOADED
 *   DISABLED → UNLOADED
 * </pre>
 *
 * @since 1.1.0
 */
public enum PluginState {

  /**
   * Plugin has been loaded and initialized, but interceptors are not yet registered. This is a
   * transitional state — plugins typically move to ACTIVE immediately.
   */
  LOADED,

  /** Plugin is fully active: initialized, enabled, and interceptors are registered. */
  ACTIVE,

  /**
   * Plugin has been disabled at runtime. Interceptors are unregistered but the plugin instance is
   * retained for potential re-enable.
   */
  DISABLED,

  /** Plugin has been fully unloaded and destroyed. Cannot transition back to any other state. */
  UNLOADED;

  /**
   * Check if this state allows transition to the target state.
   *
   * @param target the desired target state
   * @return true if the transition is valid
   */
  public boolean canTransitionTo(PluginState target) {
    switch (this) {
      case LOADED:
        return target == ACTIVE || target == UNLOADED;
      case ACTIVE:
        return target == DISABLED || target == UNLOADED;
      case DISABLED:
        return target == ACTIVE || target == UNLOADED;
      case UNLOADED:
        return false; // terminal state
      default:
        return false;
    }
  }
}
