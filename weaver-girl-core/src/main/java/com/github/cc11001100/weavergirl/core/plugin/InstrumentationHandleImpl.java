package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.plugin.InstrumentationHandle;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link InstrumentationHandle} backed by the JVM's {@link Instrumentation}.
 *
 * <p>A plugin's transformer is wrapped in a guard that declines every agent-owned class
 * ({@code com.github.cc11001100.weavergirl.*}, the shade package, Byte Buddy and ASM), because a
 * product transformer that touches the agent's own bytecode corrupts the advice inlined into
 * application classes. Declining is silent by design — the JVM treats "return null" as "not
 * interested", which is exactly the semantics needed.
 *
 * <p>Registrations are tracked per plugin so {@link #withdrawAll()} can remove them when the plugin
 * is disabled, unloaded or destroyed. A registration made on the raw {@link Instrumentation} by the
 * plugin itself is out of scope; that is what {@code InstrumentationHandle}'s rules warn about.
 *
 * @since 1.0.1
 */
final class InstrumentationHandleImpl implements InstrumentationHandle {

  /** Prefixes a product transformer must never rewrite. */
  private static final String[] AGENT_OWNED_PREFIXES = {
    "com.github.cc11001100.weavergirl.",
    "net.bytebuddy.",
    "shaded.net.bytebuddy.",
    "org.objectweb.asm."
  };

  private final Instrumentation instrumentation;
  private final List<Registration> registrations = new ArrayList<>();

  InstrumentationHandleImpl(Instrumentation instrumentation) {
    this.instrumentation = instrumentation;
  }

  @Override
  public Instrumentation getInstrumentation() {
    return instrumentation;
  }

  @Override
  public synchronized Retransformation registerTransformer(ClassFileTransformer transformer) {
    if (transformer == null) {
      throw new IllegalArgumentException("transformer must not be null");
    }
    GuardedTransformer guarded = new GuardedTransformer(transformer);
    instrumentation.addTransformer(
        guarded, instrumentation.isRetransformClassesSupported());
    Registration registration = new Registration(guarded);
    registrations.add(registration);
    return registration;
  }

  /** Remove every transformer this plugin registered. Safe to call more than once. */
  synchronized void withdrawAll() {
    for (Registration registration : registrations) {
      registration.close();
    }
    registrations.clear();
  }

  /** One transformer registration, withdrawn either by the plugin or by the agent on unload. */
  private final class Registration implements Retransformation {

    private final ClassFileTransformer guarded;
    private volatile boolean closed;

    Registration(ClassFileTransformer guarded) {
      this.guarded = guarded;
    }

    @Override
    public int retransform(Class<?>... classes) {
      if (closed || !instrumentation.isRetransformClassesSupported()) {
        return 0;
      }
      Class<?>[] targets = classes;
      if (targets == null || targets.length == 0) {
        List<Class<?>> loaded = new ArrayList<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
          if (!type.isArray() && !type.isPrimitive() && instrumentation.isModifiableClass(type)) {
            loaded.add(type);
          }
        }
        targets = loaded.toArray(new Class<?>[0]);
      }
      int retransformed = 0;
      for (Class<?> type : targets) {
        if (type == null || type.isArray() || type.isPrimitive()) {
          continue;
        }
        if (!instrumentation.isModifiableClass(type)) {
          continue;
        }
        try {
          instrumentation.retransformClasses(type);
          retransformed++;
        } catch (Throwable failure) {
          // One class the JVM declines must not abort the batch: the transformer stays useful for
          // every other class.
        }
      }
      return retransformed;
    }

    @Override
    public synchronized void close() {
      if (closed) {
        return;
      }
      closed = true;
      try {
        instrumentation.removeTransformer(guarded);
      } catch (Throwable ignored) {
        // A transformer the JVM already dropped is not an error.
      }
    }
  }

  /** Declines agent-owned classes before delegating to the product's transformer. */
  private static final class GuardedTransformer implements ClassFileTransformer {

    private final ClassFileTransformer delegate;

    GuardedTransformer(ClassFileTransformer delegate) {
      this.delegate = delegate;
    }

    @Override
    public byte[] transform(
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer) {
      if (isAgentOwned(className)) {
        return null;
      }
      try {
        return delegate.transform(
            loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
      } catch (Throwable failure) {
        // The JVM treats a thrown exception as "stop using this transformer for this class". A
        // plugin's bug must not silently disable its own instrumentation for the rest of the run.
        return null;
      }
    }

    private static boolean isAgentOwned(String className) {
      if (className == null) {
        return false;
      }
      String binaryName = className.replace('/', '.');
      for (String prefix : AGENT_OWNED_PREFIXES) {
        if (binaryName.startsWith(prefix)) {
          return true;
        }
      }
      return false;
    }
  }
}
