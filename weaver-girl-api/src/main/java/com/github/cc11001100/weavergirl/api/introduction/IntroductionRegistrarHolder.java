package com.github.cc11001100.weavergirl.api.introduction;

/**
 * Publishes the engine's {@link IntroductionRegistrar} to plugins.
 *
 * <p>Same reason as the other holders in this package: the implementing class lives in the engine
 * and is loaded in the isolated agent loader, while the plugin that reads it is loaded in a child
 * loader. This class is parent-first, so both sides see the one field.
 *
 * @since 1.0.0
 */
public final class IntroductionRegistrarHolder {

  private static volatile IntroductionRegistrar registrar;

  private IntroductionRegistrarHolder() {}

  /**
   * The registrar the agent published, or {@code null} when the agent did not publish one (a
   * bootstrap without introductions, or a unit test).
   *
   * @return the registrar, or null
   */
  public static IntroductionRegistrar get() {
    return registrar;
  }

  /**
   * Publish the registrar. Called by the engine during bootstrap; {@code null} clears it.
   *
   * @param value the registrar to publish
   */
  public static void set(IntroductionRegistrar value) {
    registrar = value;
  }
}
