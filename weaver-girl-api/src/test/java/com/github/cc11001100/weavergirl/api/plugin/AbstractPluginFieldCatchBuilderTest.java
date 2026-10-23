package com.github.cc11001100.weavergirl.api.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.interceptor.CatchInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.FieldMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Checks that the field and catch builders hand the engine exactly the shape it dispatches on: the
 * advice mode that selects the woven advice, plus an interceptor that implements {@link
 * FieldInterceptor} or {@link CatchInterceptor} and reports the pointcut the advice matches
 * against. A definition missing either half is woven with the wrong advice or never invoked.
 */
class AbstractPluginFieldCatchBuilderTest {

  private static class ProbePlugin extends AbstractPlugin {
    @Override
    public String name() {
      return "probe";
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {}
  }

  private final ProbePlugin plugin = new ProbePlugin();

  @Test
  void fieldBuilder_marksDefinitionForFieldAdvice() {
    InterceptorDefinition def =
        plugin.interceptField("com.example.Target", "secret").priority(3).build();

    assertEquals(InterceptorDefinition.AdviceMode.FIELD, def.getAdviceMode());
    assertEquals(3, def.getPriority());
    assertEquals("probe-field-com.example.Target-secret", def.getName());
    assertEquals(ClassMatcher.MatchType.EXACT_NAME, def.getPointcut().getClassMatcher().getMatchType());
    assertEquals("com.example.Target", def.getPointcut().getClassMatcher().getPattern());
    // Field selection happens inside the advice, so the method matcher only scopes the class.
    assertEquals(MethodMatcher.MatchType.ANY, def.getPointcut().getMethodMatcher().getMatchType());
  }

  @Test
  void fieldBuilder_interceptorReportsOnlyTheSelectedField() {
    InterceptorDefinition def = plugin.interceptField("com.example.Target", "secret").build();

    FieldInterceptor fieldInterceptor = (FieldInterceptor) def.getInterceptor();
    assertEquals(0, fieldInterceptor.getPriority());
    assertEquals(1, fieldInterceptor.fieldPointcuts().length);
    assertTrue(
        fieldInterceptor
            .fieldPointcuts()[0]
            .matches("com.example.Target", null, "secret", "java.lang.String"));
    assertFalse(
        fieldInterceptor
            .fieldPointcuts()[0]
            .matches("com.example.Target", null, "other", "java.lang.String"));
    assertFalse(
        fieldInterceptor
            .fieldPointcuts()[0]
            .matches("com.example.Other", null, "secret", "java.lang.String"));
  }

  @Test
  void fieldBuilder_onGetReplacesTheReadValue() {
    AtomicReference<String> seenField = new AtomicReference<String>();
    InterceptorDefinition def =
        plugin
            .interceptField("com.example.Target", "secret")
            .onGet(
                (invocation, value) -> {
                  seenField.set(invocation.getFieldName());
                  invocation.setReturnValue("redacted");
                })
            .build();

    FieldInterceptor fieldInterceptor = (FieldInterceptor) def.getInterceptor();
    FieldInvocation invocation =
        new FieldInvocation(String.class, "secret", "java.lang.String", new Object());
    fieldInterceptor.onFieldGet(invocation, "original");

    assertEquals("secret", seenField.get());
    assertTrue(invocation.isReturnOverridden());
    assertEquals("redacted", invocation.getReturnValue());
  }

  @Test
  void fieldBuilder_onSetCanRejectTheWrite() {
    AtomicInteger writes = new AtomicInteger();
    InterceptorDefinition def =
        plugin
            .interceptField("com.example.Target", FieldMatcher.byType("java.lang.String"))
            .onSet(
                (invocation, target, value) -> {
                  writes.incrementAndGet();
                  invocation.skipWrite();
                })
            .build();

    FieldInterceptor fieldInterceptor = (FieldInterceptor) def.getInterceptor();
    assertTrue(
        fieldInterceptor
            .fieldPointcuts()[0]
            .matches("com.example.Target", null, "secret", "java.lang.String"));
    assertFalse(
        fieldInterceptor
            .fieldPointcuts()[0]
            .matches("com.example.Target", null, "count", "int"));

    FieldInvocation invocation =
        new FieldInvocation(String.class, "secret", "java.lang.String", new Object());
    fieldInterceptor.onFieldSet(invocation, new Object(), "leaked");

    assertEquals(1, writes.get());
    assertTrue(invocation.isWriteSkipped());
  }

  @Test
  void fieldBuilder_withoutCallbacksDoesNothing() {
    InterceptorDefinition def = plugin.interceptField("com.example.Target", "secret").build();

    FieldInterceptor fieldInterceptor = (FieldInterceptor) def.getInterceptor();
    FieldInvocation invocation =
        new FieldInvocation(String.class, "secret", "java.lang.String", new Object());
    fieldInterceptor.onFieldGet(invocation, "original");
    fieldInterceptor.onFieldSet(invocation, new Object(), "value");

    assertFalse(invocation.isReturnOverridden());
    assertFalse(invocation.isWriteSkipped());
  }

  @Test
  void catchBuilder_marksDefinitionForCatchAdvice() {
    InterceptorDefinition def =
        plugin.interceptCatch("com.example.Target", "java.io.IOException").priority(2).build();

    assertEquals(InterceptorDefinition.AdviceMode.CATCH, def.getAdviceMode());
    assertEquals(2, def.getPriority());
    assertEquals("probe-catch-com.example.Target-java.io.IOException", def.getName());
    assertEquals(MethodMatcher.MatchType.ANY, def.getPointcut().getMethodMatcher().getMatchType());
  }

  @Test
  void catchBuilder_matchesTheExceptionAndItsSubclassesOnly() {
    InterceptorDefinition def =
        plugin.interceptCatch("com.example.Target", IOException.class.getName()).build();

    CatchInterceptor catchInterceptor = (CatchInterceptor) def.getInterceptor();
    assertEquals(0, catchInterceptor.getPriority());
    assertEquals(1, catchInterceptor.catchPointcuts().length);
    assertTrue(
        catchInterceptor.catchPointcuts()[0].matches("com.example.Target", "work", IOException.class));
    assertTrue(
        catchInterceptor
            .catchPointcuts()[0]
            .matches("com.example.Target", "work", java.net.SocketException.class));
    assertFalse(
        catchInterceptor
            .catchPointcuts()[0]
            .matches("com.example.Target", "work", IllegalStateException.class));
    assertFalse(
        catchInterceptor
            .catchPointcuts()[0]
            .matches("com.example.Other", "work", IOException.class));
  }

  @Test
  void catchBuilder_onCatchSeesTheExceptionAndCanSuppressIt() {
    AtomicReference<Throwable> seen = new AtomicReference<Throwable>();
    InterceptorDefinition def =
        plugin
            .interceptCatch("com.example.Target", IOException.class.getName())
            .onCatch(
                invocation -> {
                  seen.set(invocation.getCaughtException());
                  invocation.suppressCatch();
                })
            .build();

    IOException thrown = new IOException("disk");
    CatchInvocation invocation = new CatchInvocation(String.class, "work", thrown);
    ((CatchInterceptor) def.getInterceptor()).onCatch(invocation);

    assertTrue(seen.get() == thrown);
    assertTrue(invocation.isCatchSuppressed());
  }

  @Test
  void methodBuilder_staysOnStandardAdvice() {
    InterceptorDefinition def = plugin.intercept("com.example.Target").method("work").build();

    assertEquals(InterceptorDefinition.AdviceMode.STANDARD, def.getAdviceMode());
    assertFalse(def.getInterceptor() instanceof FieldInterceptor);
    assertFalse(def.getInterceptor() instanceof CatchInterceptor);
  }

  @Test
  void callbacks_accumulateIntoOneInterceptor() {
    java.util.List<String> calls = new java.util.ArrayList<String>();
    InterceptorDefinition def =
        plugin
            .intercept("com.example.Target")
            .method("work")
            .before(inv -> calls.add("before"))
            .after(inv -> calls.add("after"))
            .onException(inv -> calls.add("onException"))
            .build();

    com.github.cc11001100.weavergirl.api.interceptor.Interceptor interceptor =
        def.getInterceptor();
    com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation invocation =
        new com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation(
            AbstractPluginFieldCatchBuilderTest.class, "work", null, null);

    interceptor.before(invocation);
    interceptor.after(invocation);
    interceptor.onException(invocation);

    assertEquals(java.util.Arrays.asList("before", "after", "onException"), calls);
  }

  @Test
  void aroundCallback_composesWithBeforeAndExitCallbacks() {
    java.util.List<String> calls = new java.util.ArrayList<String>();
    InterceptorDefinition def =
        plugin
            .intercept("com.example.Target")
            .method("work")
            .before(inv -> calls.add("before"))
            .around(inv -> calls.add("around"))
            .after(inv -> calls.add("after"))
            .onException(inv -> calls.add("onException"))
            .build();

    com.github.cc11001100.weavergirl.api.interceptor.Interceptor interceptor =
        def.getInterceptor();
    assertTrue(interceptor.hasAround());

    com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation invocation =
        new com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation(
            AbstractPluginFieldCatchBuilderTest.class, "work", null, null);
    interceptor.around(invocation);
    interceptor.after(invocation);
    interceptor.onException(invocation);

    assertEquals(java.util.Arrays.asList("before", "around", "after", "onException"), calls);
  }

  @Test
  void twoCallbackAround_keepsAfterOnTheExceptionalPath() {
    java.util.List<String> calls = new java.util.ArrayList<String>();
    InterceptorDefinition def =
        plugin
            .intercept("com.example.Target")
            .method("work")
            .around(inv -> calls.add("around"), inv -> calls.add("after"))
            .build();

    com.github.cc11001100.weavergirl.api.interceptor.Interceptor interceptor =
        def.getInterceptor();
    assertTrue(interceptor.hasAround());

    com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation invocation =
        new com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation(
            AbstractPluginFieldCatchBuilderTest.class, "work", null, null);
    interceptor.around(invocation);
    interceptor.onException(invocation);

    assertEquals(java.util.Arrays.asList("around", "after"), calls);
  }

  @Test
  void classMatcherOverloads_buildTheSameDefinitionShape() {
    ClassMatcher matcher = ClassMatcher.byInterface("java.io.Serializable");

    InterceptorDefinition methodDef = plugin.intercept(matcher).method("work").build();
    assertEquals(ClassMatcher.MatchType.INTERFACE, methodDef.getPointcut().getClassMatcher().getMatchType());

    InterceptorDefinition fieldDef =
        plugin.interceptField(matcher, FieldMatcher.byName("secret")).build();
    assertEquals(InterceptorDefinition.AdviceMode.FIELD, fieldDef.getAdviceMode());
    assertEquals(ClassMatcher.MatchType.INTERFACE, fieldDef.getPointcut().getClassMatcher().getMatchType());

    InterceptorDefinition catchDef =
        plugin.interceptCatch(matcher, IOException.class.getName()).build();
    assertEquals(InterceptorDefinition.AdviceMode.CATCH, catchDef.getAdviceMode());
    assertTrue(
        ((CatchInterceptor) catchDef.getInterceptor())
            .catchPointcuts()[0]
            .matches(java.util.ArrayList.class, "work", IOException.class));
  }

  @Test
  void fieldAndCatchBuilders_acceptAnExplicitName() {
    assertEquals(
        "field-def",
        plugin
            .interceptField("com.example.Target", "secret")
            .name("field-def")
            .build()
            .getName());
    assertEquals(
        "catch-def",
        plugin
            .interceptCatch("com.example.Target", IOException.class.getName())
            .name("catch-def")
            .build()
            .getName());
  }
}
