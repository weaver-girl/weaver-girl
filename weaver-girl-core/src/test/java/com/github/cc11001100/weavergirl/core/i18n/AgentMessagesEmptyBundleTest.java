package com.github.cc11001100.weavergirl.core.i18n;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for AgentMessages.EmptyBundle (indirectly tested via missing resource scenarios).
 */
class AgentMessagesEmptyBundleTest {

  private Locale originalLocale;

  @BeforeEach
  void setUp() {
    originalLocale = AgentMessages.getLocale();
  }

  @AfterEach
  void tearDown() {
    AgentMessages.setLocale(originalLocale);
  }

  @Test
  void hasKey_nonexistentKey_returnsFalse() {
    boolean exists = AgentMessages.hasKey("nonexistent.key.that.does.not.exist.12345");
    assertFalse(exists);
  }

  @Test
  void msg_nonexistentKey_returnsKey() {
    String result = AgentMessages.msg("nonexistent.key.that.does.not.exist.67890");
    // AgentMessages wraps missing keys with !key!
    assertTrue(result.contains("nonexistent.key.that.does.not.exist.67890"));
  }

  @Test
  void msg_nonexistentKey_withArgs_returnsKey() {
    String result = AgentMessages.msg("nonexistent.key", "arg1", "arg2");
    // AgentMessages wraps missing keys with !key!
    assertTrue(result.contains("nonexistent.key"));
  }

  @Test
  void initializeFromSystem_withInvalidLocale_keepsDefault() {
    System.setProperty("weaver-girl.messages", "invalid-locale-format");

    try {
      // Should not throw
      assertDoesNotThrow(() -> AgentMessages.initializeFromSystem());
    } finally {
      System.clearProperty("weaver-girl.messages");
    }
  }

  @Test
  void setLocale_null_keepsCurrentLocale() {
    Locale before = AgentMessages.getLocale();
    AgentMessages.setLocale(null);
    Locale after = AgentMessages.getLocale();

    assertEquals(before, after);
  }
}
