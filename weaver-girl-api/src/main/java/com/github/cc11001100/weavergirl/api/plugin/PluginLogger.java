package com.github.cc11001100.weavergirl.api.plugin;

/**
 * Logging a plugin can do without binding to a logging framework of its own.
 *
 * <p>Obtained from {@link PluginContext#getLogger()}. The agent supplies an implementation backed by
 * its own logging, so a plugin that is loaded in an isolated class loader still writes where the
 * agent writes. Kept deliberately small: a plugin that needs structured logging ships its own.
 *
 * @since 1.0.0
 */
public interface PluginLogger {

  /** Log an informational message. {@code message} may be null. */
  void info(String message);

  /** Log a warning. {@code message} may be null. */
  void warn(String message);

  /**
   * Log an error together with its cause.
   *
   * @param message what failed, may be null
   * @param error the cause, or null when there is none
   */
  void error(String message, Throwable error);
}
