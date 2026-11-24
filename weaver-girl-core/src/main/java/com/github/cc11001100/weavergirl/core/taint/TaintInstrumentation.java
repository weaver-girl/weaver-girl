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
 * {@code Runtime}, and registers {@link TaintConcatTransformer} for Java 9+ {@code invokedynamic}
 * concatenation. The bridge classes are appended to the bootstrap loader first so advice inlined
 * into JDK methods and rewritten application call sites resolve the same {@link TaintBridge} the
 * application hooks use.
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
    "com/github/cc11001100/weavergirl/api/taint/TaintBridge$WrappedRunnable.class",
    "com/github/cc11001100/weavergirl/api/taint/TaintBridge$WrappedCallable.class",
    "com/github/cc11001100/weavergirl/api/taint/TaintPropagation.class",
    "com/github/cc11001100/weavergirl/api/taint/TaintConcat.class"
  };

  private static volatile boolean installed;
  private static volatile boolean jdkInstrumented;
  private static volatile boolean concatInstrumented;
  private static volatile boolean executorInstrumented;
  private static volatile Throwable installFailure;

  private TaintInstrumentation() {}

  public static boolean isJdkInstrumented() {
    return jdkInstrumented;
  }

  /** Whether Java 9+ {@code invokedynamic} concatenation was hooked. */
  public static boolean isConcatInstrumented() {
    return concatInstrumented;
  }

  /** Whether the JDK executor task wrapping (taint across thread pools) was installed. */
  public static boolean isExecutorInstrumented() {
    return executorInstrumented;
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
        executorInstrumented = true;
        // Only when the bridge sits on the bootstrap loader can every application class resolve
        // TaintConcat, so the concat rewrite is tied to the same condition.
        instrumentation.addTransformer(TaintConcatTransformer.INSTANCE, true);
        concatInstrumented = true;
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
            .transform(new StringTransformer())
            .type(named("java.lang.StringBuilder"))
            .transform(new StringBuilderTransformer())
            .type(named("java.lang.StringBuffer"))
            .transform(new StringBuilderTransformer())
            .type(named("java.lang.ProcessBuilder"))
            .transform(new ProcessBuilderTransformer())
            .type(named("java.lang.Runtime"))
            .transform(new RuntimeTransformer())
            // Executor submissions carry the submitting thread's scopes into the worker, which is
            // where request-scoped taint normally stops without a plugin.
            .type(named("java.util.concurrent.ThreadPoolExecutor"))
            .transform(new ExecutorTransformer())
            .type(named("java.util.concurrent.ForkJoinPool"))
            .transform(new ExecutorTransformer())
            // ScheduledThreadPoolExecutor overrides execute() and queues the task itself, so its
            // schedule() overloads are the only point where the submitting scope can be captured.
            // Only the one-shot schedule() overloads are hooked: wrapping a periodic task would
            // pin the scheduling request's scope for the lifetime of the process.
            .type(named("java.util.concurrent.ScheduledThreadPoolExecutor"))
            .transform(new ScheduledExecutorTransformer());

    builder.installOn(instrumentation);
  }

  /** Transformer for java.lang.String methods. */
  public static class StringTransformer implements AgentBuilder.Transformer {
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
                  .on(named("substring").and(takesArguments(int.class))))
          // Transforms that produce a new string: whole-result propagation keeps the flow even
          // when the source offsets cannot be mapped cheaply.
          .visit(Advice.to(StringDerivedAdvice.class).on(named("trim")))
          .visit(Advice.to(StringDerivedAdvice.class).on(named("strip")))
          .visit(Advice.to(StringDerivedAdvice.class).on(named("stripLeading")))
          .visit(Advice.to(StringDerivedAdvice.class).on(named("stripTrailing")))
          .visit(Advice.to(StringDerivedAdvice.class).on(named("toUpperCase")))
          .visit(Advice.to(StringDerivedAdvice.class).on(named("toLowerCase")))
          .visit(Advice.to(StringDerivedAdvice.class).on(named("replace")))
          .visit(Advice.to(StringDerivedAdvice.class).on(named("replaceAll")))
          .visit(Advice.to(StringDerivedAdvice.class).on(named("replaceFirst")))
          .visit(Advice.to(StringJoinAdvice.class).on(named("join")))
          // Array round trips: a string copied into chars (or bytes) and back keeps the flow.
          .visit(
              Advice.to(StringArrayConstructorAdvice.class)
                  .on(
                      ElementMatchers.isConstructor()
                          .and(
                              ElementMatchers.takesArgument(0, char[].class)
                                  .or(ElementMatchers.takesArgument(0, byte[].class)))))
          .visit(
              Advice.to(StringToCharArrayAdvice.class)
                  .on(named("toCharArray").and(takesArguments(0))))
          .visit(
              Advice.to(StringGetBytesAdvice.class)
                  .on(named("getBytes").and(ElementMatchers.returns(byte[].class))))
          // getChars writes into a caller-owned array instead of allocating a new one.
          .visit(
              Advice.to(StringGetCharsAdvice.class)
                  .on(
                      named("getChars")
                          .and(
                              takesArguments(
                                  int.class, int.class, char[].class, int.class))))
          // String.valueOf(object/char[]) is the non-concat way application code renders a value.
          .visit(
              Advice.to(StringValueOfAdvice.class)
                  .on(named("valueOf").and(ElementMatchers.returns(String.class))));
    }
  }

  /** Transformer for java.lang.StringBuilder methods. */
  public static class StringBuilderTransformer implements AgentBuilder.Transformer {
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
          // append(StringBuffer) is a distinct overload; a StringBuffer argument would otherwise
          // be appended without the advice seeing it.
          .visit(
              Advice.to(StringBuilderAppendCharSequenceAdvice.class)
                  .on(named("append").and(takesArguments(StringBuffer.class))))
          .visit(
              Advice.to(StringBuilderAppendSliceAdvice.class)
                  .on(
                      named("append")
                          .and(takesArguments(CharSequence.class, int.class, int.class))))
          .visit(
              Advice.to(StringBuilderToStringAdvice.class)
                  .on(named("toString").and(takesArguments(0))))
          // Mutations move text, so the ranges must move with it; a stale range would report a
          // slice that no longer holds the payload.
          .visit(
              Advice.to(StringBuilderDeleteAdvice.class)
                  .on(named("delete").and(takesArguments(int.class, int.class))))
          .visit(
              Advice.to(StringBuilderDeleteCharAtAdvice.class)
                  .on(named("deleteCharAt").and(takesArguments(int.class))))
          .visit(
              Advice.to(StringBuilderInsertAdvice.class)
                  .on(named("insert").and(takesArguments(int.class, String.class))))
          .visit(
              Advice.to(StringBuilderInsertCharSequenceAdvice.class)
                  .on(named("insert").and(takesArguments(int.class, CharSequence.class))))
          .visit(
              Advice.to(StringBuilderReplaceAdvice.class)
                  .on(named("replace").and(takesArguments(int.class, int.class, String.class))))
          .visit(
              Advice.to(StringBuilderSetLengthAdvice.class)
                  .on(named("setLength").and(takesArguments(int.class))))
          .visit(
              Advice.to(StringBuilderReverseAdvice.class)
                  .on(named("reverse").and(takesArguments(0))))
          // A builder seeded from a tainted string starts tainted; append(Object) and the char[]
          // overloads cover the values whose text the advice cannot read directly.
          .visit(
              Advice.to(StringBuilderAppendObjectAdvice.class)
                  .on(named("append").and(takesArguments(Object.class))))
          .visit(
              Advice.to(StringBuilderAppendArrayAdvice.class)
                  .on(named("append").and(takesArguments(char[].class))))
          .visit(
              Advice.to(StringBuilderAppendArrayRangeAdvice.class)
                  .on(named("append").and(takesArguments(char[].class, int.class, int.class))))
          .visit(
              Advice.to(StringBuilderSetCharAtAdvice.class)
                  .on(named("setCharAt").and(takesArguments(int.class, char.class))))
          .visit(
              Advice.to(StringBuilderInsertObjectAdvice.class)
                  .on(named("insert").and(takesArguments(int.class, Object.class))))
          .visit(
              Advice.to(StringBuilderInsertArrayAdvice.class)
                  .on(named("insert").and(takesArguments(int.class, char[].class))))
          .visit(
              Advice.to(StringBuilderInsertArrayRangeAdvice.class)
                  .on(
                      named("insert")
                          .and(
                              takesArguments(
                                  int.class, char[].class, int.class, int.class))));
    }
  }

  /** Transformer that carries the submitting thread's scopes into executor tasks. */
  public static class ExecutorTransformer implements AgentBuilder.Transformer {
    @Override
    public net.bytebuddy.dynamic.DynamicType.Builder<?> transform(
        net.bytebuddy.dynamic.DynamicType.Builder<?> typeBuilder,
        net.bytebuddy.description.type.TypeDescription typeDescription,
        ClassLoader classLoader,
        net.bytebuddy.utility.JavaModule module,
        java.security.ProtectionDomain protectionDomain) {
      return typeBuilder
          .visit(
              Advice.to(ExecutorExecuteAdvice.class)
                  .on(named("execute").and(takesArguments(Runnable.class))))
          // ForkJoinPool overrides submit() and never routes it through execute(), so the task
          // has to be wrapped at each declared overload.
          .visit(
              Advice.to(ForkJoinSubmitCallableAdvice.class)
                  .on(named("submit").and(takesArguments(java.util.concurrent.Callable.class))))
          .visit(
              Advice.to(ForkJoinSubmitRunnableAdvice.class)
                  .on(named("submit").and(takesArguments(Runnable.class))))
          .visit(
              Advice.to(ForkJoinSubmitRunnableResultAdvice.class)
                  .on(
                      named("submit")
                          .and(takesArguments(Runnable.class, Object.class))));
    }
  }

  /**
   * Transformer for {@code ScheduledThreadPoolExecutor}: {@code execute/submit} route through
   * {@code schedule}, which queues the task itself, so the schedule overloads are hooked instead.
   */
  public static class ScheduledExecutorTransformer implements AgentBuilder.Transformer {
    @Override
    public net.bytebuddy.dynamic.DynamicType.Builder<?> transform(
        net.bytebuddy.dynamic.DynamicType.Builder<?> typeBuilder,
        net.bytebuddy.description.type.TypeDescription typeDescription,
        ClassLoader classLoader,
        net.bytebuddy.utility.JavaModule module,
        java.security.ProtectionDomain protectionDomain) {
      return typeBuilder
          .visit(
              Advice.to(ExecutorExecuteAdvice.class)
                  .on(
                      named("schedule")
                          .and(
                              takesArguments(
                                  Runnable.class,
                                  long.class,
                                  java.util.concurrent.TimeUnit.class))))
          .visit(
              Advice.to(ForkJoinSubmitCallableAdvice.class)
                  .on(
                      named("schedule")
                          .and(
                              takesArguments(
                                  java.util.concurrent.Callable.class,
                                  long.class,
                                  java.util.concurrent.TimeUnit.class))));
    }
  }

  /** Transformer for java.lang.ProcessBuilder methods. */
  public static class ProcessBuilderTransformer implements AgentBuilder.Transformer {
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
  }

  /** Transformer for java.lang.Runtime methods. */
  public static class RuntimeTransformer implements AgentBuilder.Transformer {
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
  }

  /**
   * {@code String} constructors that copy an array: {@code String(char[])}, {@code
   * String(char[], int, int)} and the {@code byte[]} overloads.
   *
   * <p>No re-entrancy guard is taken here: ByteBuddy cannot attach exit advice that runs on a
   * throwable to a constructor, so a guard taken on entry would be stranded when the bounds check
   * throws. Propagation itself only touches collection and map helpers, not instrumented JDK
   * methods, so the guard is not needed.
   */
  public static class StringArrayConstructorAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      // The guard is probed and released immediately: ByteBuddy cannot attach exit advice that
      // runs on a throwable to a constructor, so holding it across the body would strand it
      // whenever the bounds check throws. Probing still keeps nested advice (and the bridge's own
      // static initialiser) from re-entering propagation.
      boolean active = TaintBridge.tryEnter();
      TaintBridge.release(active);
      return active;
    }

    @Advice.OnMethodExit
    public static void exit(
        @Advice.This String self,
        @Advice.AllArguments Object[] args,
        @Advice.Enter boolean active) {
      if (active) {
        TaintPropagation.propagateFromArray(self, args);
      }
    }
  }

  /** {@code String.toCharArray()}: slices map one character to one element. */
  public static class StringToCharArrayAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      return TaintBridge.tryEnter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This String self, @Advice.Return char[] result, @Advice.Enter boolean active) {
      if (thrown != null) {
        TaintBridge.release(active);
        return;
      }
      if (!active) {
        return;
      }
      try {
        if (result != null) {
          TaintPropagation.propagateToCharArray(result, self);
        }
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /**
   * {@code String.getBytes()} overloads. Decoding may change the length, so the byte array takes
   * an object-level mark and a later {@code new String(byte[])} inherits it whole.
   */
  public static class StringGetBytesAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      return TaintBridge.tryEnter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This String self, @Advice.Return byte[] result, @Advice.Enter boolean active) {
      if (thrown != null) {
        TaintBridge.release(active);
        return;
      }
      if (!active) {
        return;
      }
      try {
        if (result != null) {
          TaintPropagation.propagate(self, result);
        }
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /**
   * {@code String.valueOf(...)} overloads that return a new string. A {@code char[]} argument keeps
   * its slices; anything else (a tagged object) covers the whole rendered text. When {@code
   * valueOf(Object)} returns the very same string it was given, the call is skipped so the existing
   * precise slices are not widened.
   */
  public static class StringValueOfAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      return TaintBridge.tryEnter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.AllArguments Object[] args,
        @Advice.Return String result,
        @Advice.Enter boolean active) {
      if (thrown != null) {
        TaintBridge.release(active);
        return;
      }
      if (!active) {
        return;
      }
      try {
        // String.valueOf(String) hands back the very same instance: it already carries the mark,
        // and identity comparison is the only correct check here (not String.equals).
        if (result == null
            || args == null
            || args.length == 0
            || TaintBridge.isSameInstance(result, args[0])) {
          return;
        }
        if (args[0] instanceof char[]) {
          TaintPropagation.propagateFromArray(result, args);
        } else {
          TaintPropagation.propagateTransform(result, args);
        }
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /**
   * {@code append(Object)}: the text the builder received is whatever {@code toString()} produced,
   * so the object's mark covers the window the append added.
   */
  public static class StringBuilderAppendObjectAdvice {
    @Advice.OnMethodEnter
    public static int enter(@Advice.This CharSequence builder) {
      return TaintBridge.tryEnter() ? builder.length() : Integer.MIN_VALUE;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) Object appended,
        @Advice.Enter int offset) {
      if (thrown != null) {
        TaintBridge.release(offset != Integer.MIN_VALUE);
        return;
      }
      if (offset == Integer.MIN_VALUE) {
        return;
      }
      try {
        TaintPropagation.propagateAppendObject(builder, appended, offset, builder.length() - offset);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code append(char[])}. */
  public static class StringBuilderAppendArrayAdvice {
    @Advice.OnMethodEnter
    public static int enter(@Advice.This CharSequence builder) {
      return TaintBridge.tryEnter() ? builder.length() : Integer.MIN_VALUE;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) char[] appended,
        @Advice.Enter int offset) {
      if (thrown != null) {
        TaintBridge.release(offset != Integer.MIN_VALUE);
        return;
      }
      if (offset == Integer.MIN_VALUE) {
        return;
      }
      try {
        TaintPropagation.propagateAppendArray(
            builder, appended, offset, 0, builder.length() - offset);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code append(char[], int, int)}. */
  public static class StringBuilderAppendArrayRangeAdvice {
    @Advice.OnMethodEnter
    public static int enter(@Advice.This CharSequence builder) {
      return TaintBridge.tryEnter() ? builder.length() : Integer.MIN_VALUE;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) char[] appended,
        @Advice.Argument(1) int start,
        @Advice.Argument(2) int end,
        @Advice.Enter int offset) {
      if (thrown != null) {
        TaintBridge.release(offset != Integer.MIN_VALUE);
        return;
      }
      if (offset == Integer.MIN_VALUE) {
        return;
      }
      try {
        TaintPropagation.propagateAppendArray(builder, appended, offset, start, end - start);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code setCharAt(int, char)}: the replaced character carries no mark. */
  public static class StringBuilderSetCharAtAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      return TaintBridge.tryEnter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) int index,
        @Advice.Enter boolean active) {
      if (thrown != null) {
        TaintBridge.release(active);
        return;
      }
      if (!active) {
        return;
      }
      try {
        TaintPropagation.propagateSetCharAt(builder, index);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code insert(int, Object)}. */
  public static class StringBuilderInsertObjectAdvice {
    @Advice.OnMethodEnter
    public static int enter(@Advice.This CharSequence builder) {
      return TaintBridge.tryEnter() ? builder.length() : Integer.MIN_VALUE;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) int offset,
        @Advice.Argument(1) Object inserted,
        @Advice.Enter int before) {
      if (thrown != null) {
        TaintBridge.release(before != Integer.MIN_VALUE);
        return;
      }
      if (before == Integer.MIN_VALUE) {
        return;
      }
      try {
        TaintPropagation.propagateInsertValue(builder, offset, inserted, before);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code String.getChars(int, int, char[], int)}: writes the tainted window into the array. */
  public static class StringGetCharsAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      return TaintBridge.tryEnter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This String self,
        @Advice.Argument(0) int srcBegin,
        @Advice.Argument(1) int srcEnd,
        @Advice.Argument(2) char[] dst,
        @Advice.Argument(3) int dstBegin,
        @Advice.Enter boolean active) {
      if (thrown != null) {
        TaintBridge.release(active);
        return;
      }
      if (!active) {
        return;
      }
      try {
        TaintPropagation.propagateToCharArray(dst, self, srcBegin, srcEnd, dstBegin);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code insert(int, char[], int, int)}: only the copied window of the array is inserted. */
  public static class StringBuilderInsertArrayRangeAdvice {
    @Advice.OnMethodEnter
    public static int enter(@Advice.This CharSequence builder) {
      return TaintBridge.tryEnter() ? builder.length() : Integer.MIN_VALUE;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) int offset,
        @Advice.Argument(1) char[] inserted,
        @Advice.Argument(2) int start,
        @Advice.Argument(3) int count,
        @Advice.Enter int before) {
      if (thrown != null) {
        TaintBridge.release(before != Integer.MIN_VALUE);
        return;
      }
      if (before == Integer.MIN_VALUE) {
        return;
      }
      try {
        TaintPropagation.propagateInsertArray(builder, offset, inserted, start, count, before);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code insert(int, char[])}. */
  public static class StringBuilderInsertArrayAdvice {
    @Advice.OnMethodEnter
    public static int enter(@Advice.This CharSequence builder) {
      return TaintBridge.tryEnter() ? builder.length() : Integer.MIN_VALUE;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) int offset,
        @Advice.Argument(1) char[] inserted,
        @Advice.Enter int before) {
      if (thrown != null) {
        TaintBridge.release(before != Integer.MIN_VALUE);
        return;
      }
      if (before == Integer.MIN_VALUE) {
        return;
      }
      try {
        TaintPropagation.propagateInsertValue(builder, offset, inserted, before);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /**
   * {@code ThreadPoolExecutor.execute(Runnable)} and {@code ForkJoinPool.execute(Runnable)}: wrap
   * the task so the submitting thread's scopes are active while it runs. The wrapper returns the
   * original task when the submission happens without a scope.
   */
  public static class ExecutorExecuteAdvice {
    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
      if (task != null) {
        task = TaintBridge.wrap(task);
      }
    }
  }

  /** {@code ForkJoinPool.submit(Callable)}. */
  public static class ForkJoinSubmitCallableAdvice {
    @Advice.OnMethodEnter
    @SuppressWarnings("unchecked")
    public static void enter(
        @Advice.Argument(value = 0, readOnly = false)
            java.util.concurrent.Callable<Object> task) {
      if (task != null) {
        task = TaintBridge.wrap(task);
      }
    }
  }

  /** {@code ForkJoinPool.submit(Runnable)}. */
  public static class ForkJoinSubmitRunnableAdvice {
    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
      if (task != null) {
        task = TaintBridge.wrap(task);
      }
    }
  }

  /** {@code ForkJoinPool.submit(Runnable, T)}. */
  public static class ForkJoinSubmitRunnableResultAdvice {
    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
      if (task != null) {
        task = TaintBridge.wrap(task);
      }
    }
  }

  /** {@code String.concat(String)}. */
  public static class StringConcatAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      return TaintBridge.tryEnter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This String self,
        @Advice.Argument(0) String other,
        @Advice.Return String result,
        @Advice.Enter boolean active) {
      if (thrown != null) {
        TaintBridge.release(active);
        return;
      }
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

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This String self,
        @Advice.Argument(0) int begin,
        @Advice.Argument(1) int end,
        @Advice.Return String result,
        @Advice.Enter boolean active) {
      if (thrown != null) {
        TaintBridge.release(active);
        return;
      }
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

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This String self,
        @Advice.Argument(0) int begin,
        @Advice.Return String result,
        @Advice.Enter boolean active) {
      if (thrown != null) {
        TaintBridge.release(active);
        return;
      }
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
    public static int enter(@Advice.This CharSequence builder) {
      if (!TaintBridge.tryEnter()) {
        return Integer.MIN_VALUE;
      }
      return builder.length();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) String appended,
        @Advice.Enter int offset) {
      if (thrown != null) {
        TaintBridge.release(offset != Integer.MIN_VALUE);
        return;
      }
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
    public static int enter(@Advice.This CharSequence builder, @Advice.Argument(0) CharSequence appended) {
      if (appended instanceof String || !TaintBridge.tryEnter()) {
        return Integer.MIN_VALUE;
      }
      return builder.length();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) CharSequence appended,
        @Advice.Enter int offset) {
      if (thrown != null) {
        TaintBridge.release(offset != Integer.MIN_VALUE);
        return;
      }
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

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Return String result,
        @Advice.Enter boolean active) {
      if (thrown != null) {
        TaintBridge.release(active);
        return;
      }
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
   * Instance {@code String} methods that derive a new string from the receiver and arguments:
   * {@code trim}, {@code strip*}, case conversion, {@code replace}, {@code replaceAll},
   * {@code replaceFirst}. The whole result inherits every source label.
   */
  public static class StringDerivedAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      return TaintBridge.tryEnter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This String self,
        @Advice.AllArguments Object[] args,
        @Advice.Return String result,
        @Advice.Enter boolean active) {
      if (thrown != null) {
        TaintBridge.release(active);
        return;
      }
      if (!active) {
        return;
      }
      try {
        if (result != null) {
          TaintPropagation.propagateTransform(result, self, args);
        }
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code String.join(CharSequence, ...)}: static, so the advice takes no {@code @Advice.This}. */
  public static class StringJoinAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      return TaintBridge.tryEnter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.AllArguments Object[] args,
        @Advice.Return String result,
        @Advice.Enter boolean active) {
      if (thrown != null) {
        TaintBridge.release(active);
        return;
      }
      if (!active) {
        return;
      }
      try {
        if (result != null) {
          TaintPropagation.propagateJoin(result, args);
        }
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code StringBuilder.append(CharSequence, int, int)}. */
  public static class StringBuilderAppendSliceAdvice {
    @Advice.OnMethodEnter
    public static int enter(@Advice.This CharSequence builder) {
      if (!TaintBridge.tryEnter()) {
        return Integer.MIN_VALUE;
      }
      return builder.length();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) CharSequence appended,
        @Advice.Argument(1) int start,
        @Advice.Argument(2) int end,
        @Advice.Enter int offset) {
      if (thrown != null) {
        TaintBridge.release(offset != Integer.MIN_VALUE);
        return;
      }
      if (offset == Integer.MIN_VALUE) {
        return;
      }
      try {
        TaintPropagation.propagateAppendSlice(builder, appended, start, end, offset);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code StringBuilder.delete(int, int)}. */
  public static class StringBuilderDeleteAdvice {
    @Advice.OnMethodEnter
    public static int enter(@Advice.This CharSequence builder) {
      return TaintBridge.tryEnter() ? builder.length() : Integer.MIN_VALUE;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) int start,
        @Advice.Argument(1) int end,
        @Advice.Enter int before) {
      if (thrown != null) {
        TaintBridge.release(before != Integer.MIN_VALUE);
        return;
      }
      if (before == Integer.MIN_VALUE) {
        return;
      }
      try {
        TaintPropagation.propagateDelete(builder, start, end, before);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code StringBuilder.deleteCharAt(int)}. */
  public static class StringBuilderDeleteCharAtAdvice {
    @Advice.OnMethodEnter
    public static int enter(@Advice.This CharSequence builder) {
      return TaintBridge.tryEnter() ? builder.length() : Integer.MIN_VALUE;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) int index,
        @Advice.Enter int before) {
      if (thrown != null) {
        TaintBridge.release(before != Integer.MIN_VALUE);
        return;
      }
      if (before == Integer.MIN_VALUE) {
        return;
      }
      try {
        TaintPropagation.propagateDelete(builder, index, index + 1, before);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code StringBuilder.insert(int, String)}. */
  public static class StringBuilderInsertAdvice {
    @Advice.OnMethodEnter
    public static int enter(@Advice.This CharSequence builder) {
      return TaintBridge.tryEnter() ? builder.length() : Integer.MIN_VALUE;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) int offset,
        @Advice.Argument(1) CharSequence inserted,
        @Advice.Enter int before) {
      if (thrown != null) {
        TaintBridge.release(before != Integer.MIN_VALUE);
        return;
      }
      if (before == Integer.MIN_VALUE) {
        return;
      }
      try {
        TaintPropagation.propagateInsert(builder, offset, inserted, before);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /**
   * {@code StringBuilder.insert(int, CharSequence)}. The JDK may delegate a {@code String} to the
   * {@code insert(int, String)} overload; the re-entrancy guard keeps the outer invocation the only
   * one that rewrites ranges.
   */
  public static class StringBuilderInsertCharSequenceAdvice {
    @Advice.OnMethodEnter
    public static int enter(@Advice.This CharSequence builder) {
      return TaintBridge.tryEnter() ? builder.length() : Integer.MIN_VALUE;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) int offset,
        @Advice.Argument(1) CharSequence inserted,
        @Advice.Enter int before) {
      if (thrown != null) {
        TaintBridge.release(before != Integer.MIN_VALUE);
        return;
      }
      if (before == Integer.MIN_VALUE) {
        return;
      }
      try {
        TaintPropagation.propagateInsert(builder, offset, inserted, before);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code StringBuilder.replace(int, int, String)}. */
  public static class StringBuilderReplaceAdvice {
    @Advice.OnMethodEnter
    public static int enter(@Advice.This CharSequence builder) {
      return TaintBridge.tryEnter() ? builder.length() : Integer.MIN_VALUE;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) int start,
        @Advice.Argument(1) int end,
        @Advice.Argument(2) String inserted,
        @Advice.Enter int before) {
      if (thrown != null) {
        TaintBridge.release(before != Integer.MIN_VALUE);
        return;
      }
      if (before == Integer.MIN_VALUE) {
        return;
      }
      try {
        TaintPropagation.propagateReplace(builder, start, end, inserted, before);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code StringBuilder.setLength(int)}. */
  public static class StringBuilderSetLengthAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      return TaintBridge.tryEnter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Argument(0) int newLength,
        @Advice.Enter boolean active) {
      if (thrown != null) {
        TaintBridge.release(active);
        return;
      }
      if (!active) {
        return;
      }
      try {
        TaintPropagation.propagateSetLength(builder, newLength);
      } finally {
        TaintBridge.leave();
      }
    }
  }

  /** {@code StringBuilder.reverse()}. */
  public static class StringBuilderReverseAdvice {
    @Advice.OnMethodEnter
    public static boolean enter() {
      return TaintBridge.tryEnter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown Throwable thrown,
        @Advice.This CharSequence builder,
        @Advice.Enter boolean active) {
      if (thrown != null) {
        TaintBridge.release(active);
        return;
      }
      if (!active) {
        return;
      }
      try {
        TaintPropagation.propagateReverse(builder);
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
