package com.github.cc11001100.weavergirl.core.i18n;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight internationalization (i18n) support for the agent.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Resource bundle-based message externalization
 *   <li>Fallback to English when translations are unavailable
 *   <li>Parameterized messages with positional arguments: {@code {0}}, {@code {1}}
 *   <li>Auto-detection of locale from system properties or agent config
 *   <li>Zero-dependency (uses only JDK ResourceBundle)
 * </ul>
 *
 * <p>Message file locations:
 *
 * <pre>
 * META-INF/agent-messages.properties          (English — default)
 * META-INF/agent-messages_zh_CN.properties     (Chinese Simplified)
 * META-INF/agent-messages_ja.properties        (Japanese)
 * </pre>
 *
 * <p>Usage:
 *
 * <pre>
 * AgentMessages.msg("agent.bootstrap.started")
 * AgentMessages.msg("plugin.load.failed", pluginName, errorMsg)
 * AgentMessages.msg("circuit.open", interceptorName, threshold)
 * </pre>
 */
public final class AgentMessages {

  private static final Logger log = LoggerFactory.getLogger(AgentMessages.class);

  private static final String BUNDLE_BASE_NAME = "META-INF.agent-messages";
  private static final String BUNDLE_PROPERTY = "weaver-girl.messages";
  private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

  private static final Map<Locale, ResourceBundle> bundleCache = new ConcurrentHashMap<>();
  private static volatile Locale currentLocale = DEFAULT_LOCALE;

  private AgentMessages() {} // utility class

  // --- Static API ---

  /** Get a message by key using the current locale. */
  public static String msg(String key) {
    return msg(key, currentLocale);
  }

  /**
   * Get a parameterized message by key using the current locale. Arguments replace {0}, {1}, {2}...
   * placeholders.
   */
  public static String msg(String key, Object... args) {
    return msg(key, currentLocale, args);
  }

  /** Get a message by key using a specific locale. */
  public static String msg(String key, Locale locale) {
    return msg(key, locale, (Object[]) null);
  }

  /** Get a parameterized message by key using a specific locale. */
  public static String msg(String key, Locale locale, Object... args) {
    ResourceBundle bundle = getBundle(locale);
    String template;
    try {
      template = bundle.getString(key);
    } catch (MissingResourceException e) {
      // Try English fallback
      if (!locale.equals(DEFAULT_LOCALE)) {
        try {
          template = getBundle(DEFAULT_LOCALE).getString(key);
        } catch (MissingResourceException e2) {
          return "!" + key + "!";
        }
      } else {
        return "!" + key + "!";
      }
    }

    if (args == null || args.length == 0) {
      return template;
    }

    return formatMessage(template, args);
  }

  /** Check if a message key exists for the current locale. */
  public static boolean hasKey(String key) {
    return hasKey(key, currentLocale);
  }

  /** Check if a message key exists for a specific locale. */
  public static boolean hasKey(String key, Locale locale) {
    try {
      getBundle(locale).getString(key);
      return true;
    } catch (MissingResourceException e) {
      return false;
    }
  }

  /** Set the current locale. This affects all subsequent msg() calls. */
  public static void setLocale(Locale locale) {
    if (locale != null) {
      currentLocale = locale;
      log.debug("[i18n] Locale set to {}", locale);
    }
  }

  /** Get the current locale. */
  public static Locale getLocale() {
    return currentLocale;
  }

  /**
   * Initialize the locale from system properties or agent config. Checks in order:
   *
   * <ol>
   *   <li>{@code weaver-girl.messages} system property
   *   <li>{@code user.language} + {@code user.country} system properties
   *   <li>Default to English
   * </ol>
   */
  public static void initializeFromSystem() {
    String localeStr = System.getProperty(BUNDLE_PROPERTY);
    if (localeStr != null && !localeStr.isEmpty()) {
      Locale parsed = parseLocale(localeStr);
      if (parsed != null) {
        setLocale(parsed);
        return;
      }
    }

    // Use JVM default locale
    String language = System.getProperty("user.language", "en");
    String country = System.getProperty("user.country", "");
    Locale jvmLocale = new Locale(language, country);
    setLocale(jvmLocale);
  }

  /** Get all available message keys for the current locale. */
  public static Set<String> getKeys() {
    return getKeys(currentLocale);
  }

  /** Get all available message keys for a specific locale. */
  public static Set<String> getKeys(Locale locale) {
    Set<String> keys = new HashSet<>();
    ResourceBundle bundle = getBundle(locale);
    keys.addAll(bundle.keySet());
    return keys;
  }

  // --- Internal ---

  private static ResourceBundle getBundle(Locale locale) {
    return bundleCache.computeIfAbsent(locale, AgentMessages::loadBundle);
  }

  private static ResourceBundle loadBundle(Locale locale) {
    try {
      // Try to load from classpath with UTF-8 encoding
      String resourceName = BUNDLE_BASE_NAME.replace('.', '/');
      if (!locale.equals(DEFAULT_LOCALE)) {
        String suffix = "_" + locale.toString();
        String localized = resourceName + suffix + ".properties";
        URL url = findResource(localized);
        if (url != null) {
          return new PropertyResourceBundle(
              new InputStreamReader(url.openStream(), StandardCharsets.UTF_8));
        }
        // Try language-only
        if (!locale.getLanguage().isEmpty()) {
          String langOnly = resourceName + "_" + locale.getLanguage() + ".properties";
          url = findResource(langOnly);
          if (url != null) {
            return new PropertyResourceBundle(
                new InputStreamReader(url.openStream(), StandardCharsets.UTF_8));
          }
        }
      }
      // Fall back to default (English)
      String defaultName = resourceName + ".properties";
      URL url = findResource(defaultName);
      if (url != null) {
        return new PropertyResourceBundle(
            new InputStreamReader(url.openStream(), StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      log.warn("[i18n] Failed to load messages for locale {}: {}", locale, e.getMessage());
    }

    // Return empty bundle as last resort
    return new EmptyBundle();
  }

  private static URL findResource(String name) {
    ClassLoader cl = AgentMessages.class.getClassLoader();
    if (cl == null) cl = ClassLoader.getSystemClassLoader();
    return cl.getResource(name);
  }

  /** Simple message formatter supporting {0}, {1}, {2}... placeholders. */
  static String formatMessage(String template, Object... args) {
    if (template == null) return "";
    StringBuilder result = new StringBuilder(template.length() + 32);
    int start = 0;
    while (start < template.length()) {
      int openBrace = template.indexOf('{', start);
      if (openBrace == -1 || openBrace + 1 >= template.length()) {
        result.append(template, start, template.length());
        break;
      }
      result.append(template, start, openBrace);

      int closeBrace = template.indexOf('}', openBrace + 1);
      if (closeBrace == -1) {
        result.append(template, openBrace, template.length());
        break;
      }

      String indexStr = template.substring(openBrace + 1, closeBrace);
      try {
        int index = Integer.parseInt(indexStr);
        if (index >= 0 && index < args.length && args[index] != null) {
          result.append(args[index]);
        } else {
          result.append(template, openBrace, closeBrace + 1);
        }
      } catch (NumberFormatException e) {
        result.append(template, openBrace, closeBrace + 1);
      }
      start = closeBrace + 1;
    }
    return result.toString();
  }

  /** Parse a locale string like "zh_CN", "en", "ja_JP". */
  static Locale parseLocale(String localeStr) {
    if (localeStr == null || localeStr.isEmpty()) return null;
    String[] parts = localeStr.split("[_-]");
    if (parts.length == 1) return new Locale(parts[0]);
    if (parts.length == 2) return new Locale(parts[0], parts[1]);
    return new Locale(parts[0], parts[1], parts[2]);
  }

  /** Empty bundle that returns keys prefixed with '!' as fallback. */
  private static class EmptyBundle extends ResourceBundle {
    @Override
    protected Object handleGetObject(String key) {
      return null;
    }

    @Override
    public Enumeration<String> getKeys() {
      return Collections.emptyEnumeration();
    }
  }
}
