package com.github.cc11001100.weavergirl.core.context;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Tests for {@link MdcInjector}.
 */
class MdcInjectorTest {

  @AfterEach
  void tearDown() {
    MDC.clear();
    ThreadContext.clear();
  }

  @Test
  void inject_whenSlf4jAvailable_shouldInjectThreadContextKeys() {
    ThreadContext.put("traceId", "trace-123");
    ThreadContext.put("spanId", "span-456");
    ThreadContext.put("tenantId", "tenant-789");

    MdcInjector.MdcSnapshot snapshot = MdcInjector.inject();

    assertNotNull(snapshot);
    assertEquals("trace-123", MDC.get("traceId"));
    assertEquals("span-456", MDC.get("spanId"));
    assertEquals("tenant-789", MDC.get("tenantId"));

    // Restore to previous state
    MdcInjector.restore(snapshot);
  }

  @Test
  void restore_shouldRestorePreviousMdcState() {
    MDC.put("existingKey", "existingValue");

    MdcInjector.MdcSnapshot snapshot = MdcInjector.inject();
    MDC.put("tempKey", "tempValue");

    MdcInjector.restore(snapshot);

    // After restore, MDC is replaced with the exact state captured at snapshot time.
    assertEquals("existingValue", MDC.get("existingKey"));
    // Bridge keys are not re-applied on restore; they were only active during inject().
    assertNull(MDC.get("traceId"));
    assertNull(MDC.get("spanId"));
    assertNull(MDC.get("tenantId"));
    // Temp keys added during this method should not survive restore.
    assertNull(MDC.get("tempKey"));
  }

  @Test
  void inject_whenThreadContextEmpty_shouldNotModifyMdc() {
    ThreadContext.clear();
    MDC.put("preserveKey", "preserveValue");

    MdcInjector.MdcSnapshot snapshot = MdcInjector.inject();

    assertEquals("preserveValue", MDC.get("preserveKey"));
    assertNull(MDC.get("traceId"));
    assertNull(MDC.get("spanId"));

    MdcInjector.restore(snapshot);
    assertEquals("preserveValue", MDC.get("preserveKey"));
  }

  @Test
  void restore_withNullSnapshot_shouldBeNoop() {
    ThreadContext.put("traceId", "trace-null");

    // Should not throw
    MdcInjector.restore(null);
  }

  @Test
  void isMdcAvailable_shouldReturnTrueInTestEnvironment() {
    assertTrue(MdcInjector.isMdcAvailable());
  }
}
