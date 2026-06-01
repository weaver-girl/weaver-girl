package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the WeaverGirl programmatic API (fluent builder + registry).
 * Does NOT test withInstrumentation() which requires a real Instrumentation.
 */
class WeaverGirlTest {

    // ------------------------------------------------------------------ 1
    @Test
    @DisplayName("WeaverGirl.create() returns a non-null instance")
    void createReturnsNonNull() {
        WeaverGirl wg = WeaverGirl.create();
        assertNotNull(wg, "create() must return a non-null WeaverGirl instance");
    }

    // ------------------------------------------------------------------ 2
    @Test
    @DisplayName("intercept().method().before().install() registers an interceptor")
    void interceptBeforeInstallRegisters() {
        WeaverGirl wg = WeaverGirl.create();
        wg.intercept("com.example.Service")
          .method("process")
          .before(inv -> {})
          .install();

        List<InterceptorDefinition> defs = wg.getRegistry()
                .getInterceptorsForClass("com.example.Service");
        assertFalse(defs.isEmpty(), "Registry should contain at least one definition after install()");
    }

    // ------------------------------------------------------------------ 3
    @Test
    @DisplayName("After install, getInterceptorsForClass returns 1 definition")
    void afterInstallReturnsOneDefinition() {
        WeaverGirl wg = WeaverGirl.create();
        wg.intercept("com.example.Service")
          .method("process")
          .before(inv -> {})
          .install();

        List<InterceptorDefinition> defs = wg.getRegistry()
                .getInterceptorsForClass("com.example.Service");
        assertEquals(1, defs.size(), "Exactly one definition should be registered");
    }

    // ------------------------------------------------------------------ 4
    @Nested
    @DisplayName("Chaining before/after/onException")
    class ChainingTests {

        @Test
        @DisplayName("Multiple .before().after().onException() calls chain correctly")
        void beforeAfterOnExceptionChain() {
            List<String> callOrder = new ArrayList<>();

            WeaverGirl wg = WeaverGirl.create();
            wg.intercept("com.example.Chain")
              .method("run")
              .before(inv -> callOrder.add("before"))
              .after(inv -> callOrder.add("after"))
              .onException(inv -> callOrder.add("onException"))
              .install();

            List<InterceptorDefinition> defs = wg.getRegistry()
                    .getInterceptorsForClass("com.example.Chain");
            assertEquals(1, defs.size());

            // Invoke each callback to verify they were chained correctly
            com.github.cc11001100.weavergirl.api.interceptor.Interceptor interceptor = defs.get(0).getInterceptor();
            com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation dummyInvocation =
                    new com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation(
                            WeaverGirlTest.class, "run", null, null);

            interceptor.before(dummyInvocation);
            interceptor.after(dummyInvocation);
            interceptor.onException(dummyInvocation);

            assertEquals(List.of("before", "after", "onException"), callOrder);
        }
    }

    // ------------------------------------------------------------------ 5
    @Test
    @DisplayName("priority() sets the priority on the definition")
    void prioritySetsCorrectly() {
        WeaverGirl wg = WeaverGirl.create();
        wg.intercept("com.example.Prio")
          .method("process")
          .priority(5)
          .install();

        List<InterceptorDefinition> defs = wg.getRegistry()
                .getInterceptorsForClass("com.example.Prio");
        assertEquals(1, defs.size());
        assertEquals(5, defs.get(0).getPriority(), "Priority should be 5");
    }

    // ------------------------------------------------------------------ 6
    @Test
    @DisplayName("method(\"*\") uses MethodMatcher.any()")
    void methodWildcardUsesAnyMatcher() {
        WeaverGirl wg = WeaverGirl.create();
        wg.intercept("com.example.Wild")
          .method("*")
          .install();

        List<InterceptorDefinition> defs = wg.getRegistry()
                .getInterceptorsForClass("com.example.Wild");
        assertEquals(1, defs.size());
        MethodMatcher mm = defs.get(0).getPointcut().getMethodMatcher();
        assertEquals(MethodMatcher.MatchType.ANY, mm.getMatchType(),
                "Wildcard method '*' should produce an ANY MethodMatcher");
    }

    // ------------------------------------------------------------------ 7
    @Test
    @DisplayName("intercept without method() defaults to MethodMatcher.any()")
    void noMethodDefaultsToAnyMatcher() {
        WeaverGirl wg = WeaverGirl.create();
        wg.intercept("com.example.Default")
          .install();

        List<InterceptorDefinition> defs = wg.getRegistry()
                .getInterceptorsForClass("com.example.Default");
        assertEquals(1, defs.size());
        MethodMatcher mm = defs.get(0).getPointcut().getMethodMatcher();
        assertEquals(MethodMatcher.MatchType.ANY, mm.getMatchType(),
                "No method() call should default to ANY MethodMatcher");
    }

    // ------------------------------------------------------------------ 8
    @Test
    @DisplayName("getRegistry() returns the same registry instance")
    void getRegistryReturnsSameInstance() {
        WeaverGirl wg = WeaverGirl.create();
        InterceptorRegistry first = wg.getRegistry();
        InterceptorRegistry second = wg.getRegistry();
        assertNotNull(first, "Registry must not be null");
        assertSame(first, second, "getRegistry() must return the same instance each time");
    }
}
