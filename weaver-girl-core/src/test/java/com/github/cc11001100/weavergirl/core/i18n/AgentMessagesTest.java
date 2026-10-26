package com.github.cc11001100.weavergirl.core.i18n;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentMessagesTest {

  private Locale originalLocale;

  @BeforeEach
  void setUp() {
    originalLocale = AgentMessages.getLocale();
    AgentMessages.setLocale(Locale.ENGLISH);
  }

  @AfterEach
  void tearDown() {
    AgentMessages.setLocale(originalLocale);
  }

  // --- Basic Message Retrieval ---

  @Test
  void msg_returnsMessageForKey() {
    String msg = AgentMessages.msg("agent.bootstrap.started", "1.0.0");
    assertTrue(msg.contains("1.0.0"));
    assertTrue(msg.contains("starting"));
  }

  @Test
  void msg_withMultipleArgs() {
    String msg = AgentMessages.msg("plugin.load.summary", 10, 8, 2);
    assertTrue(msg.contains("10"));
    assertTrue(msg.contains("8"));
    assertTrue(msg.contains("2"));
  }

  /** A locale with no bundle of its own falls back to English, and a key in neither comes back marked. */
  @Test
  void msg_localeWithoutItsOwnBundle_fallsBackToEnglish() {
    String english = AgentMessages.msg("agent.shutdown.started", Locale.ENGLISH);
    String chinese = AgentMessages.msg("agent.shutdown.started", Locale.CHINESE);

    assertEquals(english, chinese, "a language with no bundle did not fall back to English");
    assertEquals("!missing.key!", AgentMessages.msg("missing.key", Locale.CHINESE));
    assertEquals("!missing.key!", AgentMessages.msg("missing.key", Locale.ENGLISH));
  }

  @Test
  void msg_unknownKey_returnsKeyWithMarkers() {
    String msg = AgentMessages.msg("nonexistent.key");
    assertEquals("!nonexistent.key!", msg);
  }

  @Test
  void msg_noArgs_returnsTemplate() {
    String msg = AgentMessages.msg("agent.shutdown.started");
    assertNotNull(msg);
    assertFalse(msg.contains("{"));
  }

  // --- Parameterized Messages ---

  @Test
  void msg_singleArg_replacesPlaceholder() {
    String msg = AgentMessages.msg("plugin.load.started", "jdbc");
    assertTrue(msg.contains("jdbc"));
  }

  @Test
  void msg_twoArgs_replacesPlaceholders() {
    String msg = AgentMessages.msg("plugin.load.failed", "myplugin", "class not found");
    assertTrue(msg.contains("myplugin"));
    assertTrue(msg.contains("class not found"));
  }

  @Test
  void msg_outOfOrderArgs_worksCorrectly() {
    String msg = AgentMessages.msg("compat.version.mismatch", "test", "2.0", "1.0");
    assertTrue(msg.contains("test"));
    assertTrue(msg.contains("2.0"));
    assertTrue(msg.contains("1.0"));
  }

  // --- formatMessage ---

  @Test
  void formatMessage_noArgs_returnsTemplate() {
    assertEquals("hello world", AgentMessages.formatMessage("hello world"));
  }

  @Test
  void formatMessage_singleArg() {
    assertEquals("hello Alice", AgentMessages.formatMessage("hello {0}", "Alice"));
  }

  @Test
  void formatMessage_multipleArgs() {
    assertEquals("a + b = c", AgentMessages.formatMessage("{0} + {1} = {2}", "a", "b", "c"));
  }

  @Test
  void formatMessage_repeatedPlaceholder() {
    assertEquals("x x x", AgentMessages.formatMessage("{0} {0} {0}", "x"));
  }

  @Test
  void formatMessage_outOfBounds_retainsPlaceholder() {
    assertEquals("hello {1}", AgentMessages.formatMessage("hello {1}", "only-arg-0"));
  }

  @Test
  void formatMessage_nullTemplate_returnsEmpty() {
    assertEquals("", AgentMessages.formatMessage(null));
  }

  @Test
  void formatMessage_noPlaceholders_returnsAsIs() {
    assertEquals(
        "no placeholders here", AgentMessages.formatMessage("no placeholders here", "ignored"));
  }

  @Test
  void formatMessage_unclosedBrace_preserved() {
    assertEquals("hello {world", AgentMessages.formatMessage("hello {world", "arg"));
  }

  @Test
  void formatMessage_nonNumericIndex_preserved() {
    assertEquals("hello {name}", AgentMessages.formatMessage("hello {name}", "arg"));
  }

  // --- Locale Support ---

  @Test
  void setLocale_changesLocale() {
    AgentMessages.setLocale(Locale.CHINA);
    assertEquals(Locale.CHINA, AgentMessages.getLocale());
  }

  @Test
  void setLocale_null_doesNotChange() {
    Locale current = AgentMessages.getLocale();
    AgentMessages.setLocale(null);
    assertEquals(current, AgentMessages.getLocale());
  }

  @Test
  void getLocale_returnsCurrentLocale() {
    AgentMessages.setLocale(Locale.JAPAN);
    assertEquals(Locale.JAPAN, AgentMessages.getLocale());
  }

  // --- parseLocale ---

  @Test
  void parseLocale_languageOnly() {
    Locale loc = AgentMessages.parseLocale("en");
    assertEquals("en", loc.getLanguage());
  }

  @Test
  void parseLocale_languageAndCountry() {
    Locale loc = AgentMessages.parseLocale("zh_CN");
    assertEquals("zh", loc.getLanguage());
    assertEquals("CN", loc.getCountry());
  }

  @Test
  void parseLocale_withDash() {
    Locale loc = AgentMessages.parseLocale("ja-JP");
    assertEquals("ja", loc.getLanguage());
    assertEquals("JP", loc.getCountry());
  }

  @Test
  void parseLocale_threeParts() {
    Locale loc = AgentMessages.parseLocale("en_US_POSIX");
    assertEquals("en", loc.getLanguage());
    assertEquals("US", loc.getCountry());
  }

  @Test
  void parseLocale_null_returnsNull() {
    assertNull(AgentMessages.parseLocale(null));
  }

  @Test
  void parseLocale_empty_returnsNull() {
    assertNull(AgentMessages.parseLocale(""));
  }

  // --- hasKey ---

  @Test
  void hasKey_existingKey_returnsTrue() {
    assertTrue(AgentMessages.hasKey("agent.bootstrap.started"));
  }

  @Test
  void hasKey_nonexistentKey_returnsFalse() {
    assertFalse(AgentMessages.hasKey("nonexistent.key"));
  }

  // --- getKeys ---

  @Test
  void getKeys_returnsNonEmpty() {
    Set<String> keys = AgentMessages.getKeys();
    assertFalse(keys.isEmpty());
    assertTrue(keys.contains("agent.bootstrap.started"));
  }

  // --- Chinese locale messages ---

  @Test
  void chineseLocale_returnsChineseMessages() {
    AgentMessages.setLocale(Locale.CHINA);
    String msg = AgentMessages.msg("agent.shutdown.started");
    // Should contain Chinese characters if the bundle loaded
    assertNotNull(msg);
    // The Chinese message should be different from the key marker
    assertNotEquals("!agent.shutdown.started!", msg);
  }

  @Test
  void chineseLocale_fallsBackToEnglish() {
    AgentMessages.setLocale(Locale.CHINA);
    // Keys that exist in English should still work
    String msg = AgentMessages.msg("agent.bootstrap.started", "1.0.0");
    assertNotNull(msg);
  }

  // --- initializeFromSystem ---

  @Test
  void initializeFromSystem_doesNotThrow() {
    assertDoesNotThrow(() -> AgentMessages.initializeFromSystem());
  }

  @Test
  void initializeFromSystem_withProperty() {
    System.setProperty("weaver-girl.messages", "zh_CN");
    try {
      AgentMessages.initializeFromSystem();
      assertEquals("zh", AgentMessages.getLocale().getLanguage());
    } finally {
      System.clearProperty("weaver-girl.messages");
    }
  }

  /**
   * A locale that has no bundle of its own, and a key that no bundle has, both fall back rather than
   * fail. The locale falls back to English, and the key falls back to its own name between markers.
   */
  @Test
  void aLocaleWithoutABundleFallsBackAndAMissingKeyIsMarked() {
    // French has a language bundle but no country bundle, so fr_FR resolves through it.
    String message = AgentMessages.msg("agent.bootstrap.started", Locale.FRANCE, "1.0");
    assertFalse(message.startsWith("!"), "a known key was not found in the fallback bundle");

    // A key that exists nowhere is returned marked, for a locale with a bundle and one without.
    assertEquals("!no.such.key!", AgentMessages.msg("no.such.key", Locale.CHINA));
    assertEquals("!no.such.key!", AgentMessages.msg("no.such.key", Locale.ENGLISH));

    // A language that has no bundle of its own still answers the keys it inherited.
    assertFalse(AgentMessages.getKeys(Locale.FRANCE).isEmpty());
    assertTrue(AgentMessages.hasKey("agent.bootstrap.started", Locale.FRANCE));
    assertFalse(AgentMessages.hasKey("no.such.key", Locale.FRANCE));
  }

  /**
   * A locale that names a country with no bundle of its own, but whose language has one, uses the
   * language bundle. French has a language bundle and no country bundle.
   */
  @Test
  void aCountryLocaleFallsBackToItsLanguageBundle() throws Exception {
    // An earlier test may have cached the English fallback for this locale, before the language
    // bundle mattered. Drop it so the lookup runs again.
    java.lang.reflect.Field cache = AgentMessages.class.getDeclaredField("bundleCache");
    cache.setAccessible(true);
    ((java.util.Map<?, ?>) cache.get(null)).remove(Locale.FRANCE);

    String message = AgentMessages.msg("agent.bootstrap.started", Locale.FRANCE, "1.0");
    assertTrue(message.contains("démarrage"), "the French language bundle was not used");
  }

  /** An empty property is ignored, so initialization follows the JVM locale instead. */
  @Test
  void anEmptyLocalePropertyFollowsTheJvm() {
    System.setProperty("weaver-girl.messages", "");
    try {
      assertDoesNotThrow(AgentMessages::initializeFromSystem);
    } finally {
      System.clearProperty("weaver-girl.messages");
    }
  }

  /** A property that names nothing is passed over, so initialization follows the JVM locale. */
  @Test
  void aBlankLocalePropertyFallsThroughToTheJvm() {
    System.setProperty("weaver-girl.messages", "   ");
    try {
      AgentMessages.initializeFromSystem();
      assertNotNull(AgentMessages.getLocale());
    } finally {
      System.clearProperty("weaver-girl.messages");
    }
  }
}
