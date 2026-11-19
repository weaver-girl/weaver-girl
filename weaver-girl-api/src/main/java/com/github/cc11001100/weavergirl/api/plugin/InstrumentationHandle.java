package com.github.cc11001100.weavergirl.api.plugin;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;

/**
 * The JVM instrumentation handle, handed to a plugin that has to weave classes the declarative
 * interceptor registry cannot reach.
 *
 * <p>{@link com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry} is the supported way
 * to add hooks: it covers methods, constructors, fields, catch blocks, static initializers and
 * introductions, and the engine takes care of advice inlining, pooling and failure isolation. This
 * handle is the escape hatch for the remaining cases — a {@code ClassFileTransformer} of the
 * product's own, bytecode the base has no matcher for (lambda metafactories, records, a vendor's
 * generated proxies), or retransformation of classes loaded before attach.
 *
 * <h3>What it can reach</h3>
 *
 * <ul>
 *   <li><b>Application classes</b> — anything the application class loaders defined. This is where
 *       every product-level rule lives.
 *   <li><b>JDK classes</b> — only the ones {@link Instrumentation#isModifiableClass(Class)} accepts.
 *       The engine already instruments what the built-in plugins need (the taint runtime weaves
 *       {@code String}, {@code ProcessBuilder} and {@code Runtime}); a product that needs more must
 *       handle class-file version and bootstrap visibility itself.
 *   <li><b>Already-loaded classes</b> — reachable through
 *       {@link Instrumentation#isRetransformClassesSupported()} +
 *       {@link Instrumentation#retransformClasses(Class[])}. Two limits are the JVM's, not the
 *       agent's: a class is not modifiable when the JVM loaded it before instrumentation was
 *       available (some bootstrap classes), and a retransform may only produce the same class
 *       shape — no new fields or methods.
 * </ul>
 *
 * <h3>Rules</h3>
 *
 * <ul>
 *   <li><b>Never weave the agent's own classes.</b> {@code com.github.cc11001100.weavergirl.*} and
 *       the shaded Byte Buddy package are off limits; the engine skips them and a transformer that
 *       does not will corrupt its own advice.
 *   <li><b>Be idempotent and fast.</b> A transformer runs on the application's class-loading
 *       thread. Return the original bytes for anything this plugin does not own, and do not throw:
 *       the JVM treats a thrown exception as "decline this transformer", which silently disables it
 *       for that class.
 *   <li><b>Clean up in {@code destroy()}.</b> {@link Retransformation} is what the agent withdraws
 *       for the plugin; a transformer the plugin adds to the raw {@link Instrumentation} must be
 *       removed by the plugin itself.
 * </ul>
 *
 * <h3>Usage</h3>
 *
 * <pre>
 * &#64;Override
 * public void init(PluginContext context) {
 *   Instrumentation instrumentation = context.getInstrumentation();
 *   if (instrumentation == null) {
 *     context.getLogger().warn("no instrumentation handle: the agent runs with a restricted host");
 *     return;
 *   }
 *   context.registerTransformer((loader, className, classBeingRedefined, domain, bytes) -&gt; ...);
 * }
 * </pre>
 *
 * @since 1.0.1
 */
public interface InstrumentationHandle {

  /**
   * The handle the JVM gave the agent, or {@code null} when the agent is running in a mode that
   * did not install one (a restricted host, or a test harness that attaches without it).
   *
   * <p>Callers must tolerate {@code null}: the contract is "ask, then decide", not "assume".
   *
   * @return the instrumentation, or null
   */
  Instrumentation getInstrumentation();

  /**
   * Register a class file transformer whose lifecycle the agent manages.
   *
   * <p>The agent removes it when the plugin is disabled, unloaded or destroyed, and it never sees
   * the agent's own classes even if the transformer asks for them. Registration is refused (and the
   * returned handle reports that) when no instrumentation is available.
   *
   * @param transformer the product's transformer
   * @return a handle whose {@link Retransformation#retransform retransform} method re-runs the
   *     registered transformer over already-loaded classes, and whose
   *     {@link Retransformation#close close} method withdraws it early
   * @throws IllegalStateException when the agent has no instrumentation handle to register on
   * @since 1.0.1
   */
  Retransformation registerTransformer(ClassFileTransformer transformer);

  /**
   * A registration made through {@link #registerTransformer}.
   *
   * @since 1.0.1
   */
  interface Retransformation extends AutoCloseable {

    /**
     * Retransform the already-loaded classes {@code transformer} might affect.
     *
     * <p>Only modifiable classes are visited, and a class the JVM declines is skipped rather than
     * failing the batch.
     *
     * @param classes the classes to revisit; empty means "every loaded class this plugin may touch"
     * @return the number of classes the JVM retransformed
     * @since 1.0.1
     */
    int retransform(Class<?>... classes);

    /**
     * Withdraw the transformer now. Calling it twice is safe; the agent calls it on unload anyway.
     *
     * @since 1.0.1
     */
    @Override
    void close();
  }
}
