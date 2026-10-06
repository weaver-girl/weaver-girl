package com.github.cc11001100.weavergirl.core.taint;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.github.cc11001100.weavergirl.api.taint.TaintBridge;
import com.github.cc11001100.weavergirl.api.taint.TaintPropagation;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs taint advice on {@code String}, {@code StringBuilder}, {@code ProcessBuilder}, and
 * {@code Runtime}. The bridge classes are appended to the bootstrap loader first so advice inlined
 * into JDK methods resolves the same {@link TaintBridge} the application hooks use.
 */
public final class TaintInstrumentation {

  private static final Logger log = LoggerFactory.getLogger(TaintInstrumentation.class);

  private static final String BRIDGE = "com.github.cc11001100.weavergirl.api.taint.TaintBridge";

  private static final String[] BRIDGE_RESOURCES = {
    "com/github/cc11001100/weavergirl/api/taint/TaintBridge.class",
    "com/github/cc11001100/weavergirl/api/taint/TaintBridge$Range.class",
    "com/github/cc11001100/weavergirl/api/taint/TaintBridge$Scope.class",
    "com/github/cc11001100/weavergirl/api/taint/TaintBridge$SnapshotData.class",
    "com/github/cc11001100/weavergirl/api/taint/TaintBridge$SinkObserver.class",
    "com/github/cc11001100/weavergirl/api/taint/TaintPropagation.class"
  };

  private static volatile boolean installed;
  private static volatile boolean jdkInstrumented;
  private static volatile Throwable installFailure;

  private TaintInstrumentation() {}

  public static boolean isJdkInstrumented() {
    return jdkInstrumented;
  }

  public static Throwable installFailure() {
    return installFailure;
  }

  public static synchronized void install(Instrumentation instrumentation) {
    if (installed || instrumentation == null) {
      return;
    }
    try {
      if (!alreadyLoaded(instrumentation, BRIDGE)) {
        appendBridge(instrumentation);
      }
      Class<?> bridge = Class.forName(BRIDGE);
      if (bridge.getClassLoader() == null) {
        instrumentJdk(instrumentation);
        jdkInstrumented = true;
      } else {
        log.info(
            "Taint bridge already loaded by {}; JDK string and process advice skipped",
            bridge.getClassLoader());
      }
      installed = true;
    } catch (Throwable t) {
      installFailure = t;
      log.warn("Taint instrumentation failed: {}", t.toString());
    }
  }

  private static boolean alreadyLoaded(Instrumentation instrumentation, String name) {
    for (Class<?> type : instrumentation.getAllLoadedClasses()) {
      if (name.equals(type.getName())) {
        return true;
      }
    }
    return false;
  }

  private static void appendBridge(Instrumentation instrumentation) throws Exception {
    File jar = File.createTempFile("weaver-taint-bridge-", ".jar");
    jar.deleteOnExit();
    ClassLoader loader = TaintInstrumentation.class.getClassLoader();
    JarOutputStream output = new JarOutputStream(new FileOutputStream(jar));
    try {
      byte[] buffer = new byte[4096];
      for (String resource : BRIDGE_RESOURCES) {
        InputStream input = loader.getResourceAsStream(resource);
        if (input == null) {
          throw new IllegalStateException("Missing taint bridge class: " + resource);
        }
        try {
          output.putNextEntry(new JarEntry(resource));
          int read;
          while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
          }
          output.closeEntry();
        } finally {
          input.close();
        }
      }
    } finally {
      output.close();
    }
    instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(jar));
  }

  private static void instrumentJdk(Instrumentation instrumentation) {
    AgentBuilder builder =
        new AgentBuilder.Default()
            .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE);

    builder =
        builder
            .type(named("java.lang.String"))
            .transform(
                new AgentBuilder.Transformer() {
                  @Override
                  public net.bytebuddy.dynamic.DynamicType.Builder<?> transform(
                      net.bytebuddy.dynamic.DynamicType.Builder<?> typeBuilder,
                      net.bytebuddy.description.type.TypeDescription typeDescription,
                      ClassLoader classLoader,
                      net.bytebuddy.utility.JavaModule module,
                      java.security.ProtectionDomain protectionDomain) {
                    return typeBuilder
                        .visit(
                            Advice.to(StringConcatAdvice.class)
                                .on(named("concat").and(takesArguments(String.class))))
                        .visit(
                            Advice.to(StringSubstringAdvice.class)
                                .on(named("substring").and(takesArguments(int.class, int.class))))
                        .visit(
                            Advice.to(StringSubstringBeginAdvice.class)
                                .on(named("substring").and(takesArguments(int.class))));
                  }
                })
            .type(named("java.lang.StringBuilder"))
            .transform(
                new AgentBuilder.Transformer() {
                  @Override
                  public net.bytebuddy.dynamic.DynamicType.Builder<?> transform(
                      net.bytebuddy.dynamic.DynamicType.Builder<?> typeBuilder,
                      net.bytebuddy.description.type.TypeDescription typeDescription,
                      ClassLoader classLoader,
                      net.bytebuddy.utility.JavaModule module,
                      java.security.ProtectionDomain protectionDomain) {
                    return typeBuilder
                        .visit(
                            Advice.to(StringBuilderAppendStringAdvice.class)
                                .on(named("append").and(takesArguments(String.class))))
                        .visit(
                            Advice.to(StringBuilderAppendCharSequenceAdvice.class)
                                .on(named("append").and(takesArguments(CharSequence.class))))
                        .visit(
                            Advice.to(StringBuilderToStringAdvice.class)
                                .on(named("toString").and(takesArguments(0))));
                  }
                })
            .type(named("java.lang.ProcessBuilder"))
            .transform(
                new AgentBuilder.Transformer() {
                  @Override
                  public net.bytebuddy.dynamic.DynamicType.Builder<?> transform(
                      net.bytebuddy.dynamic.DynamicType.Builder<?> typeBuilder,
                      net.bytebuddy.description.type.TypeDescription typeDescription,
                      ClassLoader classLoader,
                      net.bytebuddy.utility.JavaModule module,
                      java.security.ProtectionDomain protectionDomain) {
                    return typeBuilder.visit(
                        Advice.to(ProcessBuilderStartAdvice.class)
                            .on(named("start").and(takesArguments(0))));
                  }
                })
            .type(named("java.lang.Runtime"))
            .transform(
                new AgentBuilder.Transformer() {
                  @Override
                  public net.bytebuddy.dynamic.DynamicType.Builder<?> transform(
                      net.bytebuddy.dynamic.DynamicType.Builder<?> typeBuilder,
                      net.bytebuddy.description.type.TypeDescription typeDescription,
                      ClassLoader classLoader,
                      net.bytebuddy.utility.JavaModule module,
                      java.security.ProtectionDomain protectionDomain) {
                    return typeBuilder.visit(
                        Advice.to(RuntimeExecAdvice.class).on(named("exec")));
                  }
                });

    builder.installOn(instrumentation);
  }

  /** {@code String.concat(String)}. */
  public static class StringConcatAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      return TaintBridge.tryEnter();
    }

    @Advice.OnMethodExit
    public static void exit(
        @Advice.This String self,
        @Advice.Argument(0) String other,
        @Advice.Return String result,
        @Advice.Enter boolean active) {
      if (!active) {
        return;
      }
      try {
        TaintPropagation.propagateConcat(result, self, other);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code String.substring(int, int)}. */
  public static class StringSubstringAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      return TaintBridge.tryEnter();
    }

    @Advice.OnMethodExit
    public static void exit(
        @Advice.This String self,
        @Advice.Argument(0) int begin,
        @Advice.Argument(1) int end,
        @Advice.Return String result,
        @Advice.Enter boolean active) {
      if (!active) {
        return;
      }
      try {
        TaintPropagation.propagateSubstring(result, self, begin, end);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code String.substring(int)}. */
  public static class StringSubstringBeginAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      return TaintBridge.tryEnter();
    }

    @Advice.OnMethodExit
    public static void exit(
        @Advice.This String self,
        @Advice.Argument(0) int begin,
        @Advice.Return String result,
        @Advice.Enter boolean active) {
      if (!active) {
        return;
      }
      try {
        TaintPropagation.propagateSubstring(result, self, begin);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code StringBuilder.append(String)}. */
  public static class StringBuilderAppendStringAdvice {
    @Advice.OnMethodEnter
    public static int enter(@Advice.This StringBuilder builder) {
      if (!TaintBridge.tryEnter()) {
        return Integer.MIN_VALUE;
      }
      return builder.length();
    }

    @Advice.OnMethodExit
    public static void exit(
        @Advice.This StringBuilder builder,
        @Advice.Argument(0) String appended,
        @Advice.Enter int offset) {
      if (offset == Integer.MIN_VALUE) {
        return;
      }
      try {
        TaintPropagation.propagateAppend(builder, appended, offset);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /**
   * {@code StringBuilder.append(CharSequence)}. Skips {@link String} because that overload
   * delegates to {@code append(String)}, which has its own advice.
   */
  public static class StringBuilderAppendCharSequenceAdvice {
    @Advice.OnMethodEnter
    public static int enter(@Advice.This StringBuilder builder, @Advice.Argument(0) CharSequence appended) {
      if (appended instanceof String || !TaintBridge.tryEnter()) {
        return Integer.MIN_VALUE;
      }
      return builder.length();
    }

    @Advice.OnMethodExit
    public static void exit(
        @Advice.This StringBuilder builder,
        @Advice.Argument(0) CharSequence appended,
        @Advice.Enter int offset) {
      if (offset == Integer.MIN_VALUE) {
        return;
      }
      try {
        TaintPropagation.propagateAppend(builder, appended, offset);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code StringBuilder.toString()}. */
  public static class StringBuilderToStringAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      return TaintBridge.tryEnter();
    }

    @Advice.OnMethodExit
    public static void exit(
        @Advice.This StringBuilder builder,
        @Advice.Return String result,
        @Advice.Enter boolean active) {
      if (!active) {
        return;
      }
      try {
        TaintPropagation.propagateToString(result, builder);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /**
   * {@code ProcessBuilder.start()}. The re-entrancy guard stays held until exit so a nested
   * {@code Runtime.exec} inside the body does not emit the same slice again.
   */
  public static class ProcessBuilderStartAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter(@Advice.This ProcessBuilder builder) {
      boolean entered = TaintBridge.beginExecObservation();
      if (entered) {
        TaintPropagation.observeCommandList(builder.command());
      }
      return TaintBridge.suppressProcessStart;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit() {
      TaintBridge.endExecObservation();
    }
  }

  /**
   * {@code Runtime.exec} overloads. The guard is held across the body because the public overloads
   * delegate to each other.
   */
  public static class RuntimeExecAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter(@Advice.AllArguments Object[] args) {
      boolean entered = TaintBridge.beginExecObservation();
      if (entered && args != null) {
        for (Object arg : args) {
          TaintPropagation.observeCommandValue(arg);
        }
      }
      return TaintBridge.suppressProcessStart;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit() {
      TaintBridge.endExecObservation();
    }
  }
}
