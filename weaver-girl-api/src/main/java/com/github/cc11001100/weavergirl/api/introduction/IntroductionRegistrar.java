package com.github.cc11001100.weavergirl.api.introduction;

/**
 * The engine's introduction registry, published to plugins through {@link IntroductionRegistrarHolder}.
 *
 * <p>A plugin is loaded in its own class loader, so it cannot reach the engine's {@code WeaverGirl}
 * instance directly. This interface is the one handle it needs: register a definition and the engine
 * installs it on the transformer.
 *
 * @since 1.0.0
 */
public interface IntroductionRegistrar {

  /**
   * Register an introduction. A definition with the same name replaces the earlier one, so a plugin
   * re-initialised after a reload does not accumulate copies.
   *
   * @param definition the introduction to install
   * @return the name the definition is registered under
   */
  String register(IntroductionDefinition definition);

  /**
   * Remove an introduction by name.
   *
   * @param name the name passed to {@link #register}
   * @return true when a definition was removed
   */
  boolean unregister(String name);
}
