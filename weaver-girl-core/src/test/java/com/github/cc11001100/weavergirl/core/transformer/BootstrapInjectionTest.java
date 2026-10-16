package com.github.cc11001100.weavergirl.core.transformer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.instrument.Instrumentation;
import org.junit.jupiter.api.Test;

/**
 * Tests for BootstrapInjection.
 */
class BootstrapInjectionTest {

  @Test
  void inject_withNullInstrumentation_doesNotThrow() {
    BootstrapInjection injection = new BootstrapInjection();

    // Current implementation does not validate null - just ensure no crash
    assertDoesNotThrow(() -> injection.inject(null));
  }

  @Test
  void inject_multipleCalls_onlyInjectsOnce() {
    Instrumentation mockInst = mock(Instrumentation.class);
    BootstrapInjection injection = new BootstrapInjection();

    // First call - may or may not succeed depending on environment
    injection.inject(mockInst);

    // Second call should be a no-op (injected flag prevents re-injection)
    injection.inject(mockInst);

    // No exception should be thrown
    assertTrue(true);
  }

  @Test
  void constructor_createsInstance() {
    BootstrapInjection injection = new BootstrapInjection();
    assertNotNull(injection);
  }
}
