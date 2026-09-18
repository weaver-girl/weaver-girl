package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.OnConstructor;
import com.github.cc11001100.weavergirl.annotation.RewriteArg;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Locks in the {@code @RewriteArg} registration contract and the {@code @OnConstructor}
 * overload-grouping contract in {@link AnnotationPluginLoader}:
 *
 * <ul>
 *   <li>{@code @RewriteArg} produces {@link InterceptorDefinition.AdviceMode#ARGUMENT_REWRITE}
 *       definitions (woven with {@code AsyncArgumentAdvice}, so {@code setArgument} reaches the
 *       method body) — never STANDARD definitions that would be double-dispatched by {@code
 *       InterceptAdvice}.
 *   <li>{@code @RewriteArg} interceptors are before-only: after/onException/afterFinally are
 *       no-ops.
 *   <li>{@code @OnConstructor(parameterTypes=...)} groups advice methods by overload, each group
 *       getting its own CONSTRUCTOR-typed definition (not byName), so the transformer routes them
 *       to {@code ConstructorAdvice}.
 * </ul>
 */
class RewriteArgAnnotationTest {

  @WeaveClass(target = "com.example.GreeterService")
  public static class RewriteInterceptor {
    static final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    @RewriteArg("greet")
    public void upper(MethodInvocation inv) {
      calls.add("rewrite");
      inv.setArgument(0, ((String) inv.getArgument(0)).toUpperCase());
    }
  }

  @WeaveClass(target = "com.example.OverloadedService")
  public static class OverloadRewriteInterceptor {
    @RewriteArg(value = "save", parameterTypes = {"java.lang.String"})
    public void rewriteStringSave(MethodInvocation inv) {}
  }

  @WeaveClass(target = "com.example.CtorService")
  public static class GroupedConstructorInterceptor {
    static boolean anyCalled = false;
    static boolean stringCalled = false;

    @OnConstructor
    public void onAny(MethodInvocation inv) {
      anyCalled = true;
    }

    @OnConstructor(parameterTypes = {"java.lang.String"})
    public void onString(MethodInvocation inv) {
      stringCalled = true;
    }
  }

  @WeaveClass(pointcut = "execution(* com.example..*(..))")
  public static class PointcutRewriteInterceptor {
    @RewriteArg("process")
    public void rewrite(MethodInvocation inv) {}
  }

  private AnnotationPluginLoader loader;
  private InterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new DefaultInterceptorRegistry();
    RewriteInterceptor.calls.clear();
    GroupedConstructorInterceptor.anyCalled = false;
    GroupedConstructorInterceptor.stringCalled = false;
  }

  private void load(Class<?> clazz) {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(clazz);
    loader.loadAnnotatedInterceptors(classes, registry);
  }

  // ========== @RewriteArg registration ==========

  @Test
  void rewriteArg_registersArgumentRewriteDefinition() {
    load(RewriteInterceptor.class);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size(), "one @RewriteArg method should produce one definition");
    InterceptorDefinition def = defs.get(0);
    assertEquals(
        InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE,
        def.getAdviceMode(),
        "@RewriteArg must use ARGUMENT_REWRITE so the transformer weaves AsyncArgumentAdvice");
    assertEquals(
        MethodMatcher.MatchType.EXACT_NAME, def.getPointcut().getMethodMatcher().getMatchType());
    assertTrue(def.getName().contains("rewrite-"), "definition name should mark rewrite mode");
  }

  @Test
  void rewriteArg_withParameterTypes_registersSignatureMatcher() {
    load(OverloadRewriteInterceptor.class);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(1, defs.size());
    InterceptorDefinition def = defs.get(0);
    assertEquals(
        InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE, def.getAdviceMode());
    assertEquals(
        MethodMatcher.MatchType.SIGNATURE, def.getPointcut().getMethodMatcher().getMatchType());
    assertTrue(
        def.getPointcut().getMethodMatcher().matches("save", new Class<?>[] {String.class}));
    assertFalse(
        def.getPointcut().getMethodMatcher().matches("save", new Class<?>[] {Integer.class}),
        "parameterTypes must filter overloads at match time");
  }

  @Test
  void rewriteArg_interceptorRewritesArgumentViaBefore() {
    load(RewriteInterceptor.class);
    Interceptor interceptor = registry.getAllDefinitions().get(0).getInterceptor();

    MethodInvocation inv =
        new MethodInvocation(String.class, "greet", "target", new Object[] {"hello"});
    interceptor.before(inv);

    assertEquals(List.of("rewrite"), RewriteInterceptor.calls);
    assertEquals("HELLO", inv.getArgument(0), "setArgument must be visible on the invocation");
  }

  @Test
  void rewriteArg_isBeforeOnly_afterCallbacksDoNothing() {
    load(RewriteInterceptor.class);
    Interceptor interceptor = registry.getAllDefinitions().get(0).getInterceptor();

    MethodInvocation inv =
        new MethodInvocation(String.class, "greet", "target", new Object[] {"hello"});
    inv.initReturnValue("hello");
    interceptor.after(inv);
    interceptor.afterFinally(inv);

    MethodInvocation failed =
        new MethodInvocation(String.class, "greet", "target", new Object[] {"hello"});
    failed.setThrowable(new RuntimeException("boom"));
    interceptor.onException(failed);

    assertTrue(
        RewriteInterceptor.calls.isEmpty(),
        "@RewriteArg advice must not fire via after/afterFinally/onException");
  }

  @Test
  void rewriteArg_withPointcutExpression_isIgnored() {
    load(PointcutRewriteInterceptor.class);

    assertTrue(
        registry.getAllDefinitions().stream()
            .noneMatch(
                d -> d.getAdviceMode() == InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE),
        "@RewriteArg is only supported with explicit target matching, not pointcut expressions");
  }

  // ========== @OnConstructor overload grouping ==========

  @Test
  void constructor_parameterTypes_registersSeparateConstructorDefinitions() {
    load(GroupedConstructorInterceptor.class);

    List<InterceptorDefinition> defs = registry.getAllDefinitions();
    assertEquals(2, defs.size(), "each distinct constructor signature gets its own definition");
    for (InterceptorDefinition def : defs) {
      assertEquals(
          MethodMatcher.MatchType.CONSTRUCTOR,
          def.getPointcut().getMethodMatcher().getMatchType(),
          "constructor definitions must use CONSTRUCTOR matchers so the transformer routes "
              + "them to ConstructorAdvice (a byName(\"<init>\") definition would fail to bind)");
    }

    InterceptorDefinition stringDef =
        defs.stream()
            .filter(d -> d.getName().contains("java.lang.String"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no definition for the String overload"));
    assertTrue(
        stringDef.getPointcut().getMethodMatcher().matches("<init>", new Class<?>[] {String.class}));
    assertFalse(
        stringDef
            .getPointcut()
            .getMethodMatcher()
            .matches("<init>", new Class<?>[] {Integer.class}),
        "parameterTypes must filter constructor overloads");
  }

  @Test
  void constructor_groupedInterceptors_dispatchToOwnGroup() {
    load(GroupedConstructorInterceptor.class);

    InterceptorDefinition anyDef =
        registry.getAllDefinitions().stream()
            .filter(d -> d.getName().endsWith("-<init>"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no definition matching all constructors"));

    MethodInvocation inv = new MethodInvocation(String.class, "<init>", "target", new Object[0]);
    anyDef.getInterceptor().after(inv);
    assertTrue(GroupedConstructorInterceptor.anyCalled);
    assertFalse(
        GroupedConstructorInterceptor.stringCalled,
        "the String-overload group must not fire for the catch-all definition");
  }
}
