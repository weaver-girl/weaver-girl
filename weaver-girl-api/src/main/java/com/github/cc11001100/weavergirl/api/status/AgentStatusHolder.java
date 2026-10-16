package com.github.cc11001100.weavergirl.api.status;

/**
 * Process-wide holder for the {@link AgentStatusView} the engine publishes.
 *
 * <p>The engine installs its status singleton once at startup. Readers (metrics bridges, exporters)
 * that live in modules which must not depend on the engine call {@link #get()} and get {@code null}
 * until that happens, so they degrade to a no-op rather than failing.
 *
 * @since 1.0.0
 */
public final class AgentStatusHolder {

  private static volatile AgentStatusView current;

  private AgentStatusHolder() {}

  /** Install the live status view. Passing {@code null} clears it. */
  public static void set(AgentStatusView status) {
    current = status;
  }

  /** The installed status view, or {@code null} when no engine is running. */
  public static AgentStatusView get() {
    return current;
  }
}
