package com.github.cc11001100.weavergirl.core.transformer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unweave (full uninstall): clearing every interceptor and releasing the transformer must restore
 * already-woven classes to their original bytecode, so an interception that fired before the call
 * stops firing after it.
 */
class WeaverTransformerUnweaveTest {

  private static Instrumentation instrumentation;

  @BeforeAll
  static void attach() {
    try {
      instrumentation = ByteBuddyAgent.install();
    } catch (Throwable failure) {
      System.out.println("UNWEAVE-ATTACH-FAILED " + failure);
      instrumentation = null;
    }
    Assumptions.assumeTrue(instrumentation != null, "Byte Buddy attach unavailable");
  }

  @Test
  void unweaveStopsInterceptionAndRestoresOriginalBytecode() throws Exception {
    // A fresh target class every run: a name a previous test already wove is not transformed again,
    // so this test would measure nothing.
    Class<?> target =
        new ByteBuddy()
            .subclass(Object.class)
            .name("wgunweave.Target" + System.nanoTime())
            .defineMethod("ping", String.class, net.bytebuddy.description.modifier.Visibility.PUBLIC)
            .intercept(FixedValue.value("ok"))
            .make()
            .load(WeaverTransformerUnweaveTest.class.getClassLoader())
            .getLoaded();
    String self = target.getName();
    Object instance = target.getDeclaredConstructor().newInstance();
    java.lang.reflect.Method ping = target.getMethod("ping");
    assertEquals("ok", ping.invoke(instance));

    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
    AtomicInteger seen = new AtomicInteger();
    registry.register(
        new InterceptorDefinition(
            "unweave-hook",
            new Pointcut(ClassMatcher.byName(self), MethodMatcher.byName("ping")),
            new Interceptor() {
              @Override
              public void before(MethodInvocation invocation) {
                seen.incrementAndGet();
              }
            }));

    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.install(instrumentation, true);

    // Woven: ping() now fires the hook.
    assertEquals("ok", ping.invoke(instance));
    assertTrue(seen.get() > 0, "woven class must fire the interceptor");
    int before = seen.get();

    // Unweave: registry emptied, transformer reset; the class's original bytecode returns.
    int restored = transformer.unweave();
    assertTrue(restored > 0, "unweave must report at least the target class it wove");
    assertTrue(registry.getAllDefinitions().isEmpty(), "unweave must empty the registry");

    assertEquals("ok", ping.invoke(instance));
    assertEquals(
        before,
        seen.get(),
        "after unweave the interceptor must no longer fire; class restored to original bytecode");

    // A second unweave is a no-op (transformer already released).
    assertEquals(0, transformer.unweave());
  }

  @Test
  void unweaveWithoutInstallationDoesNothing() {
    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    registry.register(
        new InterceptorDefinition(
            "never-installed",
            new Pointcut(ClassMatcher.byName("com.example.Any"), MethodMatcher.any()),
            new Interceptor() {}));
    WeaverTransformer transformer = new WeaverTransformer(registry);
    assertEquals(0, transformer.unweave());
    assertFalse(
        registry.getAllDefinitions().isEmpty(),
        "no transformer was installed, so unweave must not clear the registry");
  }
}