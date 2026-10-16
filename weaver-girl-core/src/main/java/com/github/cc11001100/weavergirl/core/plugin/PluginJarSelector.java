package com.github.cc11001100.weavergirl.core.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides which capability jars are actually put on a ClassLoader.
 *
 * <p>A plugin module is one jar named {@code weaver-girl-plugin-<capability>-<version>.jar} (an
 * instrumentation plugin) or {@code weaver-girl-runtime-<capability>-<version>.jar} (one of the
 * agent's own optional capabilities). Whether it gets loaded is decided here, from the file name,
 * <em>before</em> any of its classes are loaded — so a capability that is switched off costs nothing
 * at runtime: its bytecode is never read.
 *
 * <p>Which plugins a jar carries is read from its {@code META-INF/weavergirl-capabilities} marker,
 * one plugin {@code name()} per line. That is what the configuration is matched against, so {@code
 * disabledPlugins: [exception-monitor]} keeps the exception jar off the classpath even though the
 * jar's module name ({@code exception}) differs from the plugin name. Jars without a marker fall
 * back to the name derived from the file name ({@code weaver-girl-plugin-jdbc-1.0.0.jar} → {@code
 * jdbc}); a jar that follows neither convention is always loaded.
 *
 * <p>Selection rules, applied in order:
 *
 * <ol>
 *   <li>{@code enabledPlugins} set and non-empty — only jars whose capability is listed are loaded
 *       (an allowlist; everything else stays out).
 *   <li>Otherwise {@code disabledPlugins} — every jar is loaded except the capabilities listed.
 *   <li>Neither set — every jar is loaded, which is the out-of-the-box behaviour.
 * </ol>
 */
public final class PluginJarSelector {

  private static final Logger log = LoggerFactory.getLogger(PluginJarSelector.class);

  /**
   * Every built-in capability jar starts with one of these. Instrumentation plugins use {@code
   * weaver-girl-plugin-} and the agent's own optional capabilities use {@code weaver-girl-runtime-}.
   * Jars that match neither are third-party drop-ins and always load.
   */
  static final String[] PLUGIN_JAR_PREFIXES = {"weaver-girl-plugin-", "weaver-girl-runtime-"};

  /** One plugin {@code name()} per line. Present in every built-in capability jar. */
  static final String CAPABILITY_MARKER = "META-INF/weavergirl-capabilities";

  private PluginJarSelector() {}

  /**
   * Filter a directory of jars down to the ones the configuration actually enables.
   *
   * @param jars candidate jars, typically everything in the {@code plugins/} directory
   * @param disabledPlugins comma-separated capability names to leave out; ignored when {@code
   *     enabledPlugins} is non-empty
   * @param enabledPlugins comma-separated capability names to load exclusively; empty means "no
   *     allowlist, load everything that is not disabled"
   * @return the jars to put on the ClassLoader, in the order given
   */
  public static List<File> select(List<File> jars, String disabledPlugins, String enabledPlugins) {
    if (jars == null || jars.isEmpty()) {
      return Collections.emptyList();
    }
    Set<String> enabled = parse(enabledPlugins);
    Set<String> disabled = enabled.isEmpty() ? parse(disabledPlugins) : Collections.emptySet();

    List<File> selected = new ArrayList<>();
    for (File jar : jars) {
      Set<String> capabilities = capabilitiesOf(jar);
      if (capabilities.isEmpty()) {
        // A third-party drop-in with no marker. It opts in by being present.
        selected.add(jar);
        continue;
      }
      if (!enabled.isEmpty() && disjoint(capabilities, enabled)) {
        log.info(
            "Capability {} not in enabledPlugins — its jar is not loaded", capabilities);
        continue;
      }
      if (disabled.containsAll(capabilities)) {
        log.info("Capability {} disabled — its jar is not loaded", capabilities);
        continue;
      }
      selected.add(jar);
    }
    return selected;
  }

  private static boolean disjoint(Set<String> a, Set<String> b) {
    for (String s : a) {
      if (b.contains(s)) {
        return false;
      }
    }
    return true;
  }

  /**
   * The plugin names a jar provides: the entries of its {@code META-INF/weavergirl-capabilities}
   * marker, or, when it has none, the single name derived from the file name. Empty when neither is
   * available.
   */
  static Set<String> capabilitiesOf(File jar) {
    Set<String> fromMarker = readMarker(jar);
    if (!fromMarker.isEmpty()) {
      return fromMarker;
    }
    String derived = capabilityOf(jar.getName());
    if (derived == null) {
      return Collections.emptySet();
    }
    return Collections.singleton(derived);
  }

  /** Read {@code META-INF/weavergirl-capabilities} (one plugin name per line), or empty. */
  private static Set<String> readMarker(File jar) {
    Set<String> names = new LinkedHashSet<>();
    if (jar == null || !jar.isFile()) {
      return names;
    }
    JarFile jarFile = null;
    try {
      jarFile = new JarFile(jar);
      JarEntry entry = jarFile.getJarEntry(CAPABILITY_MARKER);
      if (entry == null) {
        return names;
      }
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(jarFile.getInputStream(entry), StandardCharsets.UTF_8));
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          String trimmed = line.trim();
          if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
            names.add(trimmed);
          }
        }
      } finally {
        reader.close();
      }
    } catch (IOException e) {
      log.debug("Could not read capability marker from {}: {}", jar.getName(), e.getMessage());
    } finally {
      if (jarFile != null) {
        try {
          jarFile.close();
        } catch (IOException ignored) {
          // best effort
        }
      }
    }
    return names;
  }

  /**
   * The capability a built-in plugin jar provides, derived from its file name, or {@code null} when
   * the name follows neither the {@code weaver-girl-plugin-<capability>-<version>.jar} nor the
   * {@code weaver-girl-runtime-<capability>-<version>.jar} convention.
   */
  static String capabilityOf(String fileName) {
    String prefix = matchingPrefix(fileName);
    if (prefix == null) {
      return null;
    }
    String stem = fileName.substring(prefix.length(), fileName.length() - ".jar".length());
    // The version is the first '-' separated token that starts with a digit, so both
    // "...-jdbc-1.0.0.jar" and "...-jdbc-1.0.0-SNAPSHOT.jar" yield "jdbc". A capability name never
    // starts with a digit.
    int cut = -1;
    int from = 0;
    while (from <= stem.length()) {
      int dash = stem.indexOf('-', from);
      String token = dash < 0 ? stem.substring(from) : stem.substring(from, dash);
      if (!token.isEmpty() && Character.isDigit(token.charAt(0))) {
        cut = from - 1;
        break;
      }
      if (dash < 0) {
        break;
      }
      from = dash + 1;
    }
    if (cut <= 0) {
      return null;
    }
    return stem.substring(0, cut);
  }

  /** The capability-jar prefix {@code fileName} starts with, or {@code null} when it matches none. */
  private static String matchingPrefix(String fileName) {
    if (fileName == null || !fileName.endsWith(".jar")) {
      return null;
    }
    for (String prefix : PLUGIN_JAR_PREFIXES) {
      if (fileName.startsWith(prefix)) {
        return prefix;
      }
    }
    return null;
  }

  /** Split a comma-separated list into a de-duplicated, trimmed, order-preserving set. */
  static Set<String> parse(String csv) {
    Set<String> out = new LinkedHashSet<>();
    if (csv == null || csv.trim().isEmpty()) {
      return out;
    }
    for (String part : csv.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }
}
