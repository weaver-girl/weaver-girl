package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for WeaverGirlException.ErrorCode enum to improve coverage.
 */
class WeaverGirlExceptionErrorCodeTest {

  @Test
  void errorCode_allValues_haveValidCodes() {
    for (WeaverGirlException.ErrorCode code : WeaverGirlException.ErrorCode.values()) {
      assertTrue(code.getCode() > 0, "ErrorCode " + code + " should have positive code");
      assertNotNull(code.getDescription(), "ErrorCode " + code + " should have description");
      assertFalse(
          code.getDescription().isEmpty(), "ErrorCode " + code + " description should not be empty");
    }
  }

  @Test
  void errorCode_bootstrapFailed_hasCorrectValues() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.BOOTSTRAP_FAILED;
    assertEquals(1000, code.getCode());
    assertEquals("Agent bootstrap failed", code.getDescription());
  }

  @Test
  void errorCode_instrumentationNull_hasCorrectValues() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.INSTRUMENTATION_NULL;
    assertEquals(1001, code.getCode());
    assertEquals("Instrumentation instance is null", code.getDescription());
  }

  @Test
  void errorCode_pluginLoadFailed_hasCorrectValues() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.PLUGIN_LOAD_FAILED;
    assertEquals(2000, code.getCode());
    assertEquals("Plugin loading failed", code.getDescription());
  }

  @Test
  void errorCode_pluginInitFailed_hasCorrectValues() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.PLUGIN_INIT_FAILED;
    assertEquals(2001, code.getCode());
    assertEquals("Plugin initialization failed", code.getDescription());
  }

  @Test
  void errorCode_pluginDestroyFailed_hasCorrectValues() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.PLUGIN_DESTROY_FAILED;
    assertEquals(2002, code.getCode());
    assertEquals("Plugin destruction failed", code.getDescription());
  }

  @Test
  void errorCode_transformFailed_hasCorrectValues() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.TRANSFORM_FAILED;
    assertEquals(3000, code.getCode());
    assertEquals("Bytecode transformation failed", code.getDescription());
  }

  @Test
  void errorCode_transformerInstallFailed_hasCorrectValues() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.TRANSFORMER_INSTALL_FAILED;
    assertEquals(3001, code.getCode());
    assertEquals("Transformer installation failed", code.getDescription());
  }

  @Test
  void errorCode_interceptorBeforeFailed_hasCorrectValues() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.INTERCEPTOR_BEFORE_FAILED;
    assertEquals(4000, code.getCode());
    assertEquals("Interceptor before() callback failed", code.getDescription());
  }

  @Test
  void errorCode_interceptorAfterFailed_hasCorrectValues() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.INTERCEPTOR_AFTER_FAILED;
    assertEquals(4001, code.getCode());
    assertEquals("Interceptor after() callback failed", code.getDescription());
  }

  @Test
  void errorCode_interceptorExceptionFailed_hasCorrectValues() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.INTERCEPTOR_EXCEPTION_FAILED;
    assertEquals(4002, code.getCode());
    assertEquals("Interceptor onException() callback failed", code.getDescription());
  }

  @Test
  void errorCode_configInvalid_hasCorrectValues() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.CONFIG_INVALID;
    assertEquals(5000, code.getCode());
    assertEquals("Invalid configuration value", code.getDescription());
  }

  @Test
  void errorCode_configLoadFailed_hasCorrectValues() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.CONFIG_LOAD_FAILED;
    assertEquals(5001, code.getCode());
    assertEquals("Configuration file loading failed", code.getDescription());
  }

  @Test
  void errorCode_toString_returnsDescription() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.BOOTSTRAP_FAILED;
    String str = code.toString();
    assertNotNull(str);
  }

  @Test
  void errorCode_name_matchesEnumName() {
    assertEquals("BOOTSTRAP_FAILED", WeaverGirlException.ErrorCode.BOOTSTRAP_FAILED.name());
    assertEquals("PLUGIN_LOAD_FAILED", WeaverGirlException.ErrorCode.PLUGIN_LOAD_FAILED.name());
  }

  @Test
  void errorCode_ordinal_isUnique() {
    WeaverGirlException.ErrorCode[] codes = WeaverGirlException.ErrorCode.values();
    for (int i = 0; i < codes.length; i++) {
      assertEquals(i, codes[i].ordinal());
    }
  }

  @Test
  void errorCode_valueOf_works() {
    WeaverGirlException.ErrorCode code = WeaverGirlException.ErrorCode.valueOf("BOOTSTRAP_FAILED");
    assertNotNull(code);
  }
}
