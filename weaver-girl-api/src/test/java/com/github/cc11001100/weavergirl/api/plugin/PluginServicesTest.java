package com.github.cc11001100.weavergirl.api.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Services are how one plugin builds on another without naming it. The registry has to keep every
 * provider, hand them out in registration order, and drop one when the plugin that offered it goes away.
 */
class PluginServicesTest {

  interface Clock {
    long now();
  }

  @BeforeEach
  void noServicesYet() {
    PluginServices.clear();
  }

  @Test
  void providersAreReturnedInRegistrationOrder() {
    Clock first = () -> 1L;
    Clock second = () -> 2L;

    PluginServices.register(Clock.class, first);
    PluginServices.register(Clock.class, second);
    PluginServices.register(Clock.class, first);

    List<Clock> clocks = PluginServices.get(Clock.class);
    assertEquals(2, clocks.size(), "registering the same instance twice published it twice");
    assertSame(first, clocks.get(0));
    assertSame(second, clocks.get(1));
  }

  @Test
  void unregisterRemovesOnlyThatProvider() {
    Clock kept = () -> 1L;
    Clock dropped = () -> 2L;
    PluginServices.register(Clock.class, kept);
    PluginServices.register(Clock.class, dropped);

    assertEquals(1, PluginServices.unregister(dropped));

    List<Clock> clocks = PluginServices.get(Clock.class);
    assertEquals(1, clocks.size());
    assertSame(kept, clocks.get(0));
  }

  @Test
  void aServiceNobodyRegisteredIsAnEmptyList() {
    assertTrue(PluginServices.get(Clock.class).isEmpty());
  }

  @Test
  void anImplementationOfTheWrongTypeIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> PluginServices.register(Clock.class, "no"));
  }
}
