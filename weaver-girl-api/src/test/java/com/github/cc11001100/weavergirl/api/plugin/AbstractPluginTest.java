package com.github.cc11001100.weavergirl.api.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.CatchInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.FieldMatcher;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Every builder entry point produces a definition, and the callbacks it captured actually run. */
class AbstractPluginTest {

  private final TestPlugin plugin = new TestPlugin();

  @Test
  void instrumentedLibraryIsReportedOnlyAfterItIsNamed() {
    assertNull(plugin.instrumentedLibrary());

    plugin.nameTheLibrary();

    assertEquals("io.grpc.Server", plugin.instrumentedLibrary());
  }

  @Test
  void classMatchersSelectByNamePatternAnnotationSuperTypeAndInterface() {
    assertTrue(plugin.intercept("com.example.Service").build().getName().contains("com.example.Service"));
    assertTrue(
        plugin
            .interceptClassPattern("com\\.example\\..*")
            .build()
            .getName()
            .contains("com\\.example"));
    assertTrue(
        plugin.interceptAnnotated("com.example.Traced").build().getName().contains("Traced"));
    assertTrue(
        plugin.interceptSubclassOf("com.example.Base").build().getName().contains("Base"));
    assertTrue(
        plugin.interceptImplementing("java.io.Serializable").build().getName().contains("Serializable"));
    assertTrue(
        plugin
            .intercept(ClassMatcher.byName("com.example.Other"))
            .build()
            .getName()
            .contains("Other"));
  }

  @Test
  void methodMatchersAndAnExplicitNameAreRecorded() {
    InterceptorDefinition definition =
        plugin
            .intercept("com.example.Service")
            .method("process")
            .name("custom-name")
            .priority(7)
            .build();

    assertEquals("custom-name", definition.getName());
    assertEquals(7, definition.getPriority());
    assertTrue(definition.getPointcut().getMethodMatcher().matches("process"));
    assertFalse(definition.getPointcut().getMethodMatcher().matches("other"));

    assertTrue(
        plugin
            .intercept("com.example.Service")
            .methodPattern("proc.*")
            .build()
            .getPointcut()
            .getMethodMatcher()
            .matches("process"));
    assertTrue(
        plugin
            .intercept("com.example.Service")
            .methodAnnotated("com.example.Timed")
            .anyMethod()
            .build()
            .getPointcut()
            .getMethodMatcher()
            .matches("anything"));
  }

  @Test
  void beforeAfterAndExceptionCallbacksAllFire() {
    AtomicInteger before = new AtomicInteger();
    AtomicInteger after = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();
    InterceptorDefinition definition =
        plugin
            .intercept("com.example.Service")
            .method("process")
            .before(invocation -> before.incrementAndGet())
            .after(invocation -> after.incrementAndGet())
            .onException(invocation -> failed.incrementAndGet())
            .build();

    MethodInvocation invocation = invocation();
    definition.getInterceptor().before(invocation);
    definition.getInterceptor().after(invocation);
    definition.getInterceptor().onException(invocation);

    assertEquals(1, before.get());
    assertEquals(1, after.get());
    assertEquals(1, failed.get());
    assertFalse(definition.getInterceptor().hasAround());
  }

  @Test
  void aroundRunsTheBeforeCallbackThenTheAroundCallback() {
    StringBuilder order = new StringBuilder();
    InterceptorDefinition definition =
        plugin
            .intercept("com.example.Service")
            .around(
                invocation -> order.append("around"),
                invocation -> order.append("ok"),
                invocation -> order.append("bad"))
            .before(invocation -> order.append("before"))
            .build();

    assertTrue(definition.getInterceptor().hasAround());
    definition.getInterceptor().around(invocation());
    definition.getInterceptor().after(invocation());
    definition.getInterceptor().onException(invocation());

    assertEquals("beforearoundokbad", order.toString());
  }

  @Test
  void theTwoCallbackAroundAlsoRunsOnTheExitPaths() {
    AtomicInteger exits = new AtomicInteger();
    InterceptorDefinition definition =
        plugin
            .intercept("com.example.Service")
            .around(invocation -> {}, invocation -> exits.incrementAndGet())
            .build();

    definition.getInterceptor().around(invocation());
    definition.getInterceptor().after(invocation());
    definition.getInterceptor().onException(invocation());

    assertEquals(2, exits.get());
  }

  @Test
  void aDefinitionWithoutCallbacksIsANoOp() {
    InterceptorDefinition definition = plugin.intercept("com.example.Service").build();

    assertDoesNotThrow(() -> definition.getInterceptor().before(invocation()));
    assertDoesNotThrow(() -> definition.getInterceptor().after(invocation()));
    assertDoesNotThrow(() -> definition.getInterceptor().onException(invocation()));
  }

  @Test
  void fieldDefinitionsCarryFieldAdviceAndDispatchBothCallbacks() {
    AtomicInteger reads = new AtomicInteger();
    AtomicInteger writes = new AtomicInteger();
    InterceptorDefinition definition =
        plugin
            .interceptField("com.example.Service", "balance")
            .onGet((invocation, value) -> reads.incrementAndGet())
            .onSet((invocation, target, value) -> writes.incrementAndGet())
            .priority(3)
            .name("balance-watch")
            .build();

    assertEquals("balance-watch", definition.getName());
    assertEquals(InterceptorDefinition.AdviceMode.FIELD, definition.getAdviceMode());
    FieldInterceptor fieldInterceptor = (FieldInterceptor) definition.getInterceptor();
    assertEquals(1, fieldInterceptor.fieldPointcuts().length);
    assertEquals(3, fieldInterceptor.getPriority());
    fieldInterceptor.onFieldGet(null, "read");
    fieldInterceptor.onFieldSet(null, new Object(), "write");
    assertEquals(1, reads.get());
    assertEquals(1, writes.get());

    InterceptorDefinition byMatcher =
        plugin
            .interceptField(ClassMatcher.byName("com.example.Service"), FieldMatcher.byType("int"))
            .build();
    assertEquals(InterceptorDefinition.AdviceMode.FIELD, byMatcher.getAdviceMode());
    assertTrue(byMatcher.getName().contains("int"));

    InterceptorDefinition byClassMatcher =
        plugin.interceptField(ClassMatcher.byInterface("com.example.Account"), "balance").build();
    assertTrue(byClassMatcher.getName().contains("balance"));
    ((FieldInterceptor) byClassMatcher.getInterceptor()).onFieldGet(null, null);
    ((FieldInterceptor) byClassMatcher.getInterceptor()).onFieldSet(null, null, null);
  }

  @Test
  void catchDefinitionsCarryCatchAdviceAndDispatchTheCallback() {
    AtomicInteger caught = new AtomicInteger();
    InterceptorDefinition definition =
        plugin
            .interceptCatch("com.example.Service", "java.io.IOException")
            .onCatch(invocation -> caught.incrementAndGet())
            .priority(4)
            .name("io-watch")
            .build();

    assertEquals("io-watch", definition.getName());
    assertEquals(InterceptorDefinition.AdviceMode.CATCH, definition.getAdviceMode());
    CatchInterceptor catchInterceptor = (CatchInterceptor) definition.getInterceptor();
    assertEquals(1, catchInterceptor.catchPointcuts().length);
    assertEquals(4, catchInterceptor.getPriority());
    catchInterceptor.onCatch(null);
    assertEquals(1, caught.get());

    InterceptorDefinition byMatcher =
        plugin.interceptCatch(ClassMatcher.byName("com.example.Service"), "java.lang.RuntimeException")
            .build();
    assertTrue(byMatcher.getName().contains("RuntimeException"));
    ((CatchInterceptor) byMatcher.getInterceptor()).onCatch(null);
  }

  private static MethodInvocation invocation() {
    return new MethodInvocation(AbstractPluginTest.class, "run", null, new Object[0]);
  }

  private static final class TestPlugin extends AbstractPlugin {
    @Override
    public String name() {
      return "test-plugin";
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {}

    void nameTheLibrary() {
      instrumentedLibrary("io.grpc.Server");
    }
  }
}
