package com.github.cc11001100.weavergirl.core;

import java.lang.instrument.Instrumentation;

/**
 * Holds the {@link Instrumentation} the agent was started with, so a plugin can instrument classes
 * the ordinary transformer does not reach.
 *
 * <p>This lives here, and not on the agent class, for the same reason as {@link
 * InterceptorHolder}'s retransform hook: the agent installs the value from outside the isolated
 * loader, while the plugin that reads it is loaded inside. This class is resolved parent-first, so
 * both sides share the one field.
 */
public final class AgentInstrumentation {

  private static volatile Instrumentation instrumentation;

  private AgentInstrumentation() {}

  /** Install the instrumentation the agent was given. {@code null} clears it. */
  public static void set(Instrumentation value) {
    instrumentation = value;
  }

  /** The installed instrumentation, or {@code null} when the agent has not published one. */
  public static Instrumentation get() {
    return instrumentation;
  }
}
