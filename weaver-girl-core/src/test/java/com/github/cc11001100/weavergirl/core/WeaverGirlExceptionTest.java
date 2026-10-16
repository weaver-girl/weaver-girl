package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Basic tests for WeaverGirlException. */
class WeaverGirlExceptionTest {

  @Test
  void weaverGirlException_canBeCreated_withMessage() {
    WeaverGirlException ex = new WeaverGirlException(WeaverGirlException.ErrorCode.BOOTSTRAP_FAILED, "test error");
    assertTrue(ex.getMessage().contains("test error"));
    assertEquals(WeaverGirlException.ErrorCode.BOOTSTRAP_FAILED, ex.getErrorCode());
  }

  @Test
  void weaverGirlException_canBeCreated_withMessageAndCause() {
    Exception cause = new RuntimeException("root cause");
    WeaverGirlException ex = new WeaverGirlException(WeaverGirlException.ErrorCode.BOOTSTRAP_FAILED, "wrapped error", cause);
    assertTrue(ex.getMessage().contains("wrapped error"));
    assertEquals(cause, ex.getCause());
    assertEquals(WeaverGirlException.ErrorCode.BOOTSTRAP_FAILED, ex.getErrorCode());
  }

  @Test
  void errorCode_enum_hasValues() {
    WeaverGirlException.ErrorCode[] codes = WeaverGirlException.ErrorCode.values();
    assertTrue(codes.length > 0);
  }

  @Test
  void errorCode_valueOf_works() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.valueOf("BOOTSTRAP_FAILED");
    assertNotNull(code);
  }

  @Test
  void errorCode_getCode_works() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.BOOTSTRAP_FAILED;
    assertTrue(code.getCode() > 0);
  }

  /** An exception built from a cause alone still names the code and the cause. */
  @Test
  void anExceptionFromACauseCarriesBoth() {
    IllegalStateException cause = new IllegalStateException("disk full");
    WeaverGirlException ex =
        new WeaverGirlException(WeaverGirlException.ErrorCode.BOOTSTRAP_FAILED, cause);

    assertSame(cause, ex.getCause());
    assertTrue(ex.getMessage().contains("disk full"));
    assertEquals(WeaverGirlException.ErrorCode.BOOTSTRAP_FAILED, ex.getErrorCode());
  }

  @Test
  void errorCode_getDescription_works() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.BOOTSTRAP_FAILED;
    assertNotNull(code.getDescription());
  }
}
