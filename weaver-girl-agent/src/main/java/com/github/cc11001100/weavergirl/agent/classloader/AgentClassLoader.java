package com.github.cc11001100.weavergirl.agent.classloader;

import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Child-first (parent-last) ClassLoader that isolates the agent body — most importantly ByteBuddy
 * and the agent's own internals — from the host application's system ClassLoader.
 *
 * <h2>Why this exists</h2>
 *
 * Without an isolated loader, every class in the packaged agent jar lands on the JVM's system
 * ClassLoader (that is how {@code -javaagent} works). An APM/IAST hook base deployed into arbitrary
 * apps will then collide with the host's own ByteBuddy / library versions: first-definer wins, and
 * a version mismatch produces {@link LinkageError} at the host's own expense. Running the agent
 * body in this child-first loader means the agent resolves <em>its</em> ByteBuddy (and its
 * internals) from the agent jar, regardless of what the host carries.
 *
 * <h2>The advice-bridge constraint</h2>
 *
 * ByteBuddy {@code @Advice} is <em>inlined</em>: the advice method's bytecode is copied into each
 * instrumented target, where it then executes on whatever ClassLoader loaded the target. The advice
 * references a small set of "bridge" classes (the interceptor holder, status, sampling, etc.).
 * Those references must resolve to the <strong>same</strong> Class objects that the agent wrote the
 * interceptor registry into — otherwise the advice silently reads a {@code null} registry and
 * interception stops working with no error.
 *
 * <p>To guarantee that, the bridge classes are loaded <strong>parent-first</strong> (i.e. by the
 * system loader, exactly as before isolation). Everything else — ByteBuddy, the transformer, plugin
 * loader, config, metrics, the agent's own entry point — is loaded child-first from the agent jar.
 * The bridge closure is kept closed (bridge classes only reference one another, the JDK, and the
 * public API), so there are no downward references that would break loading.
 *
 * <h2>Loading order</h2>
 *
 * <ol>
 *   <li>Already loaded? return it.
 *   <li>Bridge / API / JDK / relocated-logging package? delegate to parent first.
 *   <li>Otherwise load child-first from the agent jar.
 *   <li>Not found locally? fall back to the parent loader.
 * </ol>
 *
 * <p>This mirrors {@link com.github.cc11001100.weavergirl.core.plugin.PluginClassLoader}'s approach
 * (parent-first security packages, child-first everything else) but with a larger parent-first set
 * that covers the advice bridge. It is Java-8 compatible.
 */
public final class AgentClassLoader extends URLClassLoader {

  static {
    // Register as parallel-capable so fine-grained locking is possible on Java 7+.
    // (isRegisteredAsParallelCapable() is true after this returns true.)
    boolean registered = registerAsParallelCapable();
    assert registered : "AgentClassLoader must register as parallel-capable";
  }

  /**
   * Package prefixes always delegated to the parent (system) loader FIRST.
   *
   * <ul>
   *   <li>JDK packages — always parent-first.
   *   <li>{@code com.sun.} — the JDK's own {@code com.sun.net.httpserver} used by the agent's
   *       health/metrics HTTP servers.
   *   <li>Relocated SLF4J ({@code ...shade.org.slf4j}) — used by the bridge classes, so kept on the
   *       system loader to avoid a split binding. It is namespaced, so it still never clashes with
   *       the host's own {@code org.slf4j}.
   *   <li>{@code ...api.} — the public API. Referenced by the inlined advice AND by plugins; must
   *       be one shared set of Class objects across the isolated and system loaders.
   *   <li>The advice-bridge packages — classes the inlined {@code InterceptAdvice} references at
   *       runtime. Parent-first so {@code setRegistry} (isolated) and the advice (target loader →
   *       system) share one instance.
   * </ul>
   */
  private static final Set<String> PARENT_FIRST_PREFIXES;

  static {
    Set<String> set =
        new HashSet<>(
            Arrays.asList(
                // JDK
                "java.",
                "javax.",
                "sun.",
                "jdk.",
                "org.w3c.dom.",
                "org.xml.sax.",
                "org.ietf.jgss.",
                "org.omg.",
                // JDK HTTP server used by the agent's health/metrics endpoints
                "com.sun.",
                // Relocated logging facade (namespaced — still isolated from host)
                "com.github.cc11001100.weavergirl.shade.org.slf4j.",
                // Public API — shared across the isolated/system boundary
                "com.github.cc11001100.weavergirl.api.",
                // Advice-bridge packages (closed set: only reference each other + API + JDK)
                "com.github.cc11001100.weavergirl.core.interceptor.",
                "com.github.cc11001100.weavergirl.core.sampling.",
                "com.github.cc11001100.weavergirl.core.status.",
                "com.github.cc11001100.weavergirl.core.switches.",
                "com.github.cc11001100.weavergirl.core.circuit.",
                "com.github.cc11001100.weavergirl.core.management."));
    PARENT_FIRST_PREFIXES = Collections.unmodifiableSet(set);
  }

  /**
   * Specific fully-qualified bridge classes that live in the {@code core} root package. The root
   * package also holds {@code WeaverGirl} and {@code InterceptAdvice}, which must stay isolated, so
   * the whole package cannot be parent-first — we list the bridge members individually.
   */
  private static final Set<String> PARENT_FIRST_CLASSES;

  static {
    Set<String> set = new HashSet<>();
    // The advice reads/writes the registry and switches through InterceptorHolder,
    // so it MUST resolve to the system-loaded instance setRegistry targeted.
    set.add("com.github.cc11001100.weavergirl.core.InterceptorHolder");
    PARENT_FIRST_CLASSES = Collections.unmodifiableSet(set);
  }

  public AgentClassLoader(URL[] urls, ClassLoader parent) {
    super(urls, parent);
  }

  /**
   * Build an isolated loader over the jar that defines {@code anchor} (the agent entry class), with
   * the system ClassLoader as parent.
   *
   * @param anchor a class packaged inside the agent jar
   * @return a new isolated agent ClassLoader
   */
  public static AgentClassLoader create(Class<?> anchor) {
    URL[] urls = agentUrls(anchor);
    ClassLoader parent = ClassLoader.getSystemClassLoader();
    return new AgentClassLoader(urls, parent);
  }

  /** Resolve the agent jar URL from a class packaged inside it. */
  private static URL[] agentUrls(Class<?> anchor) {
    ProtectionDomain pd = anchor.getProtectionDomain();
    CodeSource cs = pd != null ? pd.getCodeSource() : null;
    URL url = cs != null ? cs.getLocation() : null;
    if (url == null) {
      throw new IllegalStateException(
          "Could not determine agent jar location from "
              + anchor.getName()
              + " (no ProtectionDomain/CodeSource) — cannot build isolated loader");
    }
    return new URL[] {url};
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    // Java 8 compatible: synchronized(this) instead of getClassLoadingLock().
    synchronized (this) {
      Class<?> c = findLoadedClass(name);
      if (c == null) {
        if (isParentFirst(name)) {
          try {
            c = super.loadClass(name, false);
          } catch (ClassNotFoundException ignored) {
            c = null; // fall through to child-first
          }
        }
        if (c == null) {
          try {
            c = findClass(name);
          } catch (ClassNotFoundException ignored) {
            c = null; // fall through to parent fallback
          }
        }
        if (c == null) {
          c = super.loadClass(name, false);
        }
      }
      if (resolve) {
        resolveClass(c);
      }
      return c;
    }
  }

  private boolean isParentFirst(String name) {
    if (PARENT_FIRST_CLASSES.contains(name)) {
      return true;
    }
    for (String prefix : PARENT_FIRST_PREFIXES) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
