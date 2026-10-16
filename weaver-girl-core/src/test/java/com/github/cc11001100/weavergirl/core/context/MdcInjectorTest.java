package com.github.cc11001100.weavergirl.core.context;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import java.lang.reflect.Method;
import java.util.Deque;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;

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

  /** A bridge key already holding the same value is left untouched. */
  @Test
  void aBridgeKeyThatAlreadyMatchesIsNotRewritten() {
    ThreadContext.put("traceId", "trace-123");
    MDC.put("traceId", "trace-123");

    MdcInjector.inject();

    assertEquals("trace-123", MDC.get("traceId"));
  }

  /** A bridge key left behind by an earlier injection is removed once the context no longer has it. */
  @Test
  void aStaleBridgeKeyIsRemoved() {
    MDC.put("traceId", "left-over");
    ThreadContext.clear();

    MdcInjector.inject();

    assertNull(MDC.get("traceId"), "a stale bridge key survived the injection");
  }

  /** An empty bridge value counts as absent, so it clears the key instead of writing a blank. */
  @Test
  void anEmptyBridgeValueClearsTheKey() {
    MDC.put("spanId", "old");
    ThreadContext.put("spanId", "");

    MdcInjector.inject();

    assertNull(MDC.get("spanId"));
  }

  /**
   * An MDC that throws on every call must not break the intercepted method. Injection and restoration
   * both swallow the failure, and availability reports false.
   */
  @Test
  void aBrokenMdcIsSurvived() throws Exception {
    Method setter = MDC.class.getDeclaredMethod("setMDCAdapter", MDCAdapter.class);
    setter.setAccessible(true);
    Object previous = MDC.getMDCAdapter();
    setter.invoke(null, new BrokenMdcAdapter());
    try {
      ThreadContext.put("traceId", "trace-123");

      MdcInjector.MdcSnapshot snapshot = MdcInjector.inject();
      assertNotNull(snapshot, "injection aborted when the MDC threw");
      MdcInjector.restore(snapshot);
      assertFalse(MdcInjector.isMdcAvailable(), "a broken MDC was reported as usable");
    } finally {
      setter.invoke(null, previous);
    }
  }

  /** An adapter whose every operation fails, so each MDC call throws. */
  private static final class BrokenMdcAdapter implements MDCAdapter {
    private static UnsupportedOperationException broken() {
      return new UnsupportedOperationException("mdc unavailable");
    }

    public void put(String key, String val) {
      throw broken();
    }

    public String get(String key) {
      throw broken();
    }

    public void remove(String key) {
      throw broken();
    }

    public void clear() {
      throw broken();
    }

    public Map<String, String> getCopyOfContextMap() {
      throw broken();
    }

    public void setContextMap(Map<String, String> contextMap) {
      throw broken();
    }

    public void pushByKey(String key, String value) {
      throw broken();
    }

    public String popByKey(String key) {
      throw broken();
    }

    public Deque<String> getCopyOfDequeByKey(String key) {
      throw broken();
    }

    public void clearDequeByKey(String key) {
      throw broken();
    }
  }
}
