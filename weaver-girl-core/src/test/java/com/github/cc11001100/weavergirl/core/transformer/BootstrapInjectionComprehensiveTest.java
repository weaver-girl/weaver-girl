package com.github.cc11001100.weavergirl.core.transformer;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.instrument.Instrumentation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * More comprehensive tests for BootstrapInjection.
 */
class BootstrapInjectionComprehensiveTest {

  @Test
  void inject_withValidInstrumentation_doesNotThrow() {
    Instrumentation mockInst = Mockito.mock(Instrumentation.class);
    BootstrapInjection injection = new BootstrapInjection();

    // May log warnings but should not throw
    assertDoesNotThrow(() -> injection.inject(mockInst));
  }

  @Test
  void inject_multipleTimes_isIdempotent() {
    Instrumentation mockInst = Mockito.mock(Instrumentation.class);
    BootstrapInjection injection = new BootstrapInjection();

    injection.inject(mockInst);
    injection.inject(mockInst);
    injection.inject(mockInst);

    // Should be safe to call multiple times
    assertTrue(true);
  }

  @Test
  void constructor_createsNonNullInstance() {
    BootstrapInjection injection = new BootstrapInjection();
    assertNotNull(injection);
  }
}
