package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.api.plugin.ProbePlugin;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link FieldAdvice} and {@link CatchAdvice} with definitions a plugin built through {@link
 * AbstractPlugin}, which is the path a third-party plugin actually takes. The advice classes select
 * interceptors by interface rather than by the definition's advice mode, so this is what proves the
 * builder wired the two halves together.
 */
class PluginFieldCatchDispatchTest {

  /** Stand-in application class the advice inspects reflectively. */
  public static class Target {
    public String secret = "original";

    public void work() {}
  }

  private DefaultInterceptorRegistry registry;
  private final ProbePlugin plugin = new ProbePlugin();

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
    InterceptorHolder.setRegistry(registry);
    resetGlobalInterceptionSwitch();
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
  }

  @AfterEach
  void tearDown() {
    InterceptorHolder.setRegistry(null);
    resetGlobalInterceptionSwitch();
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
  }

  /**
   * {@code GlobalInterceptionSwitch.resetForTest()} is package-private to the {@code switches}
   * package, so tests outside it reset both fields via reflection. Copied from {@code
   * FieldAdviceTest}, which has to do the same.
   */
  private static void resetGlobalInterceptionSwitch() {
    try {
      Field enabledField = GlobalInterceptionSwitch.class.getDeclaredField("enabled");
      enabledField.setAccessible(true);
      enabledField.setBoolean(null, true);
      Field toggleCountField = GlobalInterceptionSwitch.class.getDeclaredField("toggleCount");
      toggleCountField.setAccessible(true);
      ((java.util.concurrent.atomic.AtomicLong) toggleCountField.get(null)).set(0);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void pluginFieldInterceptor_firesOnReadAndCanReplaceTheValue() throws Exception {
    AtomicReference<String> seen = new AtomicReference<String>();
    registry.register(
        plugin.fieldRead(
            Target.class.getName(),
            "secret",
            (invocation, value) -> {
              seen.set(invocation.getFieldName());
              invocation.setReturnValue("redacted");
            }));

    Field field = Target.class.getDeclaredField("secret");
    FieldInvocation result = FieldAdvice.onFieldEnter(Target.class, field, new Target(), null);

    assertEquals("secret", seen.get());
    assertTrue(result.isReturnOverridden());
    assertEquals("redacted", result.getReturnValue());
  }

  @Test
  void pluginFieldInterceptor_firesOnWriteAndCanRejectIt() throws Exception {
    AtomicInteger writes = new AtomicInteger();
    registry.register(
        plugin.fieldWrite(
            Target.class.getName(),
            "secret",
            (invocation, target, value) -> {
              writes.incrementAndGet();
              assertEquals("leaked", value);
              invocation.skipWrite();
            }));

    Field field = Target.class.getDeclaredField("secret");
    FieldInvocation result = FieldAdvice.onFieldEnter(Target.class, field, new Target(), "leaked");

    assertEquals(1, writes.get());
    assertTrue(result.isWriteSkipped());
  }

  @Test
  void pluginFieldInterceptor_ignoresADifferentClass() throws Exception {
    AtomicInteger reads = new AtomicInteger();
    registry.register(
        plugin.fieldRead(
            "com.example.Nobody", "secret", (invocation, value) -> reads.incrementAndGet()));

    Field field = Target.class.getDeclaredField("secret");
    FieldAdvice.onFieldEnter(Target.class, field, new Target(), null);

    assertEquals(0, reads.get());
  }

  @Test
  void pluginCatchInterceptor_firesAndCanSuppress() throws Exception {
    AtomicReference<Throwable> seen = new AtomicReference<Throwable>();
    registry.register(
        plugin.catching(
            Target.class.getName(),
            IOException.class.getName(),
            invocation -> {
              seen.set(invocation.getCaughtException());
              invocation.suppressCatch();
            }));

    IOException thrown = new IOException("disk");
    Method method = Target.class.getDeclaredMethod("work");
    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(Target.class, method, thrown, registry);

    assertSame(thrown, seen.get());
    assertTrue(result.suppressed);
  }

  @Test
  void pluginCatchInterceptor_ignoresAnUnrelatedException() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    registry.register(
        plugin.catching(
            Target.class.getName(),
            IOException.class.getName(),
            invocation -> hits.incrementAndGet()));

    Method method = Target.class.getDeclaredMethod("work");
    CatchAdvice.CatchDisposition result =
        CatchAdvice.dispatch(Target.class, method, new IllegalArgumentException("no"), registry);

    assertEquals(0, hits.get());
    assertNull(result);
  }
}
