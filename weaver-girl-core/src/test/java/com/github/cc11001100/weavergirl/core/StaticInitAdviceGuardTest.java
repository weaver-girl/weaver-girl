package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The static-initializer hook must stay silent when interception is off or the call is not sampled,
 * and a hook that throws must not stop class initialization.
 */
class StaticInitAdviceGuardTest {

  private static Instrumentation instrumentation;

  @BeforeAll
  static void attach() {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Throwable failure) {
      instrumentation = null;
    }
    Assumptions.assumeTrue(instrumentation != null, "Byte Buddy attach unavailable");
  }

  @AfterEach
  void reset() {
    InterceptorHolder.setRegistry(null);
    setInterceptionEnabled(true);
    SamplingController.getInstance().setMaxRate(100);
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
  }

  @Test
  void theHookFiresWhenInterceptionIsOn() throws Exception {
    AtomicInteger calls = new AtomicInteger();

    Class<?> target = weave(recording(calls));

    assertEquals("ready", target.getField("STATE").get(null));
    assertEquals(2, calls.get());
  }

  @Test
  void aDisabledSwitchSkipsTheHookButTheClassStillInitializes() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    setInterceptionEnabled(false);

    Class<?> target = weave(recording(calls));

    assertEquals("ready", target.getField("STATE").get(null));
    assertEquals(0, calls.get());
  }

  @Test
  void aDroppedSampleSkipsTheBeforeHook() throws Exception {
    AtomicInteger beforeCalls = new AtomicInteger();
    AtomicInteger afterCalls = new AtomicInteger();

    // A dropped sample returns null from the enter advice, so the before hook never runs. The exit
    // advice has no sampling check of its own: it rebuilds the invocation and still runs after.
    Class<?> target =
        weave(
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                beforeCalls.incrementAndGet();
              }

              @Override
              public void after(MethodInvocation invocation) {
                afterCalls.incrementAndGet();
              }
            },
            false);

    assertEquals("ready", target.getField("STATE").get(null));
    assertEquals(0, beforeCalls.get());
    assertEquals(1, afterCalls.get());
  }

  @Test
  void aThrowingHookDoesNotAbortClassInitialization() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    Class<?> target =
        weave(
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                calls.incrementAndGet();
                throw new IllegalStateException("hook failed");
              }

              @Override
              public void after(MethodInvocation invocation) {
                calls.incrementAndGet();
                throw new IllegalStateException("hook failed again");
              }
            });

    assertEquals("ready", target.getField("STATE").get(null));
    assertEquals(2, calls.get());
  }

  private static Interceptor recording(final AtomicInteger calls) {
    return new Interceptor() {
      @Override
      public void before(MethodInvocation invocation) {
        calls.incrementAndGet();
      }

      @Override
      public void after(MethodInvocation invocation) {
        calls.incrementAndGet();
      }
    };
  }

  private static Class<?> weave(Interceptor interceptor) throws Exception {
    return weave(interceptor, true);
  }

  private static Class<?> weave(Interceptor interceptor, boolean sampled) throws Exception {
    String name = "wgclinit.Target" + System.nanoTime();
    byte[] bytecode = classWithStaticInitializer(name);
    // The transformer's existence check only registers an exact-name interceptor when the class file is
    // already visible as a resource. Write it where the context class loader will find it, then remove
    // it so only this one class is woven.
    Path classFile = exposeAsResource(name, bytecode);
    try {
      DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
      InterceptorHolder.setRegistry(registry);
      registry.register(
          new InterceptorDefinition(
              "clinit-" + name,
              new Pointcut(ClassMatcher.byName(name), MethodMatcher.byName("<clinit>")),
              interceptor,
              0,
              InterceptorDefinition.AdviceMode.STATIC_INIT));
      new WeaverTransformer(registry).install(instrumentation, true);
    } finally {
      Files.deleteIfExists(classFile);
    }

    // defineClass must run on the normal load path (findClass called from loadClass) so the installed
    // transformer sees the bytes. A ClassLoader that calls defineClass from anywhere else bypasses it.
    ClassLoader loader =
        new ClassLoader(StaticInitAdviceGuardTest.class.getClassLoader()) {
          @Override
          protected Class<?> findClass(String wanted) throws ClassNotFoundException {
            if (name.equals(wanted)) {
              return defineClass(wanted, bytecode, 0, bytecode.length);
            }
            throw new ClassNotFoundException(wanted);
          }
        };
    // Set the sampling decision immediately before initialization. install() retransforms classes
    // already loaded by other tests, and their hooks move the shared counter.
    // The rate is capped by the controller's max rate, and the counter is shared with every other
    // woven hook in this JVM, so a small rate can land exactly on a sampled count. A rate past any
    // count this one class initialization can reach drops the sample for certain.
    SamplingController controller = SamplingController.getInstance();
    if (sampled) {
      controller.setSamplingRate(1);
    } else {
      controller.setMaxRate(Integer.MAX_VALUE);
      controller.setSamplingRate(Integer.MAX_VALUE);
      controller.resetCounter();
    }
    return Class.forName(name, true, loader);
  }

  /** Puts the class file on the context class loader's classpath and returns the path to delete. */
  private static Path exposeAsResource(String name, byte[] bytecode) throws IOException {
    String classpath = System.getProperty("surefire.test.class.path");
    if (classpath == null || classpath.isEmpty()) {
      classpath = System.getProperty("java.class.path");
    }
    Path root = null;
    for (String entry : classpath.split(java.io.File.pathSeparator)) {
      Path candidate = java.nio.file.Paths.get(entry);
      if (Files.isDirectory(candidate)) {
        root = candidate;
        break;
      }
    }
    if (root == null) {
      throw new IllegalStateException("no directory on the classpath to expose " + name);
    }
    Path classFile = root.resolve(name.replace('.', '/') + ".class");
    Files.createDirectories(classFile.getParent());
    Files.write(classFile, bytecode);
    return classFile;
  }

  /** A class whose only job is {@code public static String STATE = "ready";}. */
  private static byte[] classWithStaticInitializer(String name) {
    String internal = name.replace('.', '/');
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
    writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "STATE", "Ljava/lang/String;", null, null);
    MethodVisitor initializer = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
    initializer.visitCode();
    initializer.visitLdcInsn("ready");
    initializer.visitFieldInsn(Opcodes.PUTSTATIC, internal, "STATE", "Ljava/lang/String;");
    initializer.visitInsn(Opcodes.RETURN);
    initializer.visitMaxs(0, 0);
    initializer.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  private static void setInterceptionEnabled(boolean enabled) {
    try {
      Field field = GlobalInterceptionSwitch.class.getDeclaredField("enabled");
      field.setAccessible(true);
      field.setBoolean(null, enabled);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
