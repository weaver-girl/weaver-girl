package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.InstrumentationHandle;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The instrumentation escape hatch: a plugin can reach bytecode the declarative registry cannot,
 * and the agent owns the transformer's lifecycle.
 *
 * <p>Uses a mocked {@link Instrumentation} for the bookkeeping, and asserts the guard that keeps a
 * product transformer away from the agent's own classes — the one rule that would corrupt the
 * agent's inlined advice if it were broken.
 */
class InstrumentationHandleImplTest {

  private static DefaultPluginContext contextWith(Instrumentation instrumentation) {
    return new DefaultPluginContext(
        Mockito.mock(InterceptorRegistry.class),
        Collections.<String, String>emptyMap(),
        "escape-hatch-test",
        instrumentation);
  }

  @Test
  void noInstrumentationMeansNoHandleAndALoudFailure() {
    DefaultPluginContext context =
        new DefaultPluginContext(
            Mockito.mock(InterceptorRegistry.class),
            Collections.<String, String>emptyMap(),
            "no-instrumentation");

    assertNull(context.getInstrumentation(), "a context without a handle must say so");
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> context.registerTransformer((loader, name, redefined, domain, bytes) -> null));
    assertTrue(
        failure.getMessage().contains("no-instrumentation"),
        "the failure must name the plugin that asked: " + failure.getMessage());
  }

  @Test
  void registrationIsTrackedAndWithdrawnWithThePlugin() {
    Instrumentation instrumentation = Mockito.mock(Instrumentation.class);
    Mockito.when(instrumentation.isRetransformClassesSupported()).thenReturn(true);
    DefaultPluginContext context = contextWith(instrumentation);

    InstrumentationHandle.Retransformation registration =
        context.registerTransformer((loader, name, redefined, domain, bytes) -> null);
    assertNotNull(registration);

    Mockito.verify(instrumentation).addTransformer(Mockito.any(ClassFileTransformer.class), Mockito.eq(true));

    // Withdrawing the plugin's services takes the transformer with it: a plugin that is disabled
    // must stop rewriting bytecode, not keep doing it until the process exits.
    context.withdrawServices();
    Mockito.verify(instrumentation).removeTransformer(Mockito.any(ClassFileTransformer.class));

    // Closing twice is safe, and closing after the withdrawal is a no-op.
    registration.close();
    registration.close();
  }

  @Test
  void aProductTransformerNeverSeesTheAgentsOwnClasses() throws Exception {
    Instrumentation instrumentation = Mockito.mock(Instrumentation.class);
    DefaultPluginContext context = contextWith(instrumentation);
    AtomicInteger calls = new AtomicInteger();

    InstrumentationHandle.Retransformation registration =
        context.registerTransformer(
            (loader, name, redefined, domain, bytes) -> {
              calls.incrementAndGet();
              return bytes;
            });
    assertNotNull(registration);

    // The guard is inside the registered transformer, so exercise it through the real class.
    ClassFileTransformer guarded =
        (ClassFileTransformer)
            Mockito.mockingDetails(instrumentation)
                .getInvocations()
                .stream()
                .filter(i -> i.getMethod().getName().equals("addTransformer"))
                .findFirst()
                .map(i -> i.getArgument(0))
                .orElse(null);
    assertNotNull(guarded, "the plugin transformer must be registered");

    assertEquals(
        null,
        guarded.transform(
            null, "com/github/cc11001100/weavergirl/core/InterceptAdvice", null, (ProtectionDomain) null, new byte[0]),
        "agent-owned classes must be declined");
    assertEquals(
        null,
        guarded.transform(null, "net/bytebuddy/agent/builder/AgentBuilder", null, (ProtectionDomain) null, new byte[0]),
        "Byte Buddy must be declined");
    assertEquals(0, calls.get(), "the product transformer must not even be called");

    assertNotNull(
        guarded.transform(null, "com/example/OrderService", null, (ProtectionDomain) null, new byte[] {1}),
        "an application class goes to the product transformer");
    assertEquals(1, calls.get());
  }

  @Test
  void aBrokenProductTransformerDoesNotTakeItselfOut() throws Exception {
    Instrumentation instrumentation = Mockito.mock(Instrumentation.class);
    DefaultPluginContext context = contextWith(instrumentation);

    InstrumentationHandle.Retransformation registration =
        context.registerTransformer(
            (loader, name, redefined, domain, bytes) -> {
              throw new IllegalStateException("bug in the product transformer");
            });
    assertNotNull(registration);

    ClassFileTransformer guarded =
        (ClassFileTransformer)
            Mockito.mockingDetails(instrumentation)
                .getInvocations()
                .stream()
                .filter(i -> i.getMethod().getName().equals("addTransformer"))
                .findFirst()
                .map(i -> i.getArgument(0))
                .orElse(null);
    assertNotNull(guarded);

    // The JVM stops using a transformer that throws; the guard swallows the error for that class so
    // the plugin's own bug cannot silently disable its instrumentation for the rest of the run.
    assertNull(
        guarded.transform(
            null, "com/example/OrderService", null, (ProtectionDomain) null, new byte[0]));
  }

  @Test
  void retransformSkipsClassesTheJvmDeclines() throws Exception {
    Instrumentation instrumentation = Mockito.mock(Instrumentation.class);
    Mockito.when(instrumentation.isRetransformClassesSupported()).thenReturn(true);
    Mockito.when(instrumentation.isModifiableClass(Mockito.any(Class.class))).thenReturn(true);
    Mockito.doThrow(new UnsupportedOperationException("declined"))
        .when(instrumentation)
        .retransformClasses(Mockito.any(Class[].class));

    DefaultPluginContext context = contextWith(instrumentation);
    InstrumentationHandle.Retransformation registration =
        context.registerTransformer((loader, name, redefined, domain, bytes) -> null);

    assertEquals(0, registration.retransform(String.class), "a declined class is not counted");
  }
}
