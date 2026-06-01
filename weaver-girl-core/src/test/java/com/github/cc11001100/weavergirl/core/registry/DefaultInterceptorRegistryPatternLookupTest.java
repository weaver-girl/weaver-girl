// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/registry/DefaultInterceptorRegistryPatternLookupTest.java
package com.github.cc11001100.weavergirl.core.registry;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that DefaultInterceptorRegistry correctly resolves non-EXACT_NAME matchers
 * (NAME_PATTERN, ANNOTATION, SUPER_CLASS, INTERFACE) at lookup time.
 */
class DefaultInterceptorRegistryPatternLookupTest {

    private DefaultInterceptorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultInterceptorRegistry();
    }

    @Test
    void namePatternMatcher_matchesClassName() {
        InterceptorDefinition def = new InterceptorDefinition("pattern-interceptor",
                new Pointcut(ClassMatcher.byNamePattern("com\\.example\\..*Service"), MethodMatcher.byName("doWork")),
                new Interceptor() {});
        registry.register(def);

        List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.UserService");
        assertEquals(1, result.size());
        assertEquals("pattern-interceptor", result.get(0).getName());
    }

    @Test
    void exactNameMatcher_stillWorks() {
        InterceptorDefinition def = new InterceptorDefinition("exact-interceptor",
                new Pointcut(ClassMatcher.byName("com.example.UserService"), MethodMatcher.byName("doWork")),
                new Interceptor() {});
        registry.register(def);

        List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.UserService");
        assertEquals(1, result.size());
        assertEquals("exact-interceptor", result.get(0).getName());
    }

    @Test
    void bothExactAndPatternMatch_returnedTogether() {
        InterceptorDefinition exactDef = new InterceptorDefinition("exact-interceptor",
                new Pointcut(ClassMatcher.byName("com.example.UserService"), MethodMatcher.byName("doWork")),
                new Interceptor() {});

        InterceptorDefinition patternDef = new InterceptorDefinition("pattern-interceptor",
                new Pointcut(ClassMatcher.byNamePattern("com\\.example\\..*Service"), MethodMatcher.byName("doWork")),
                new Interceptor() {});

        registry.register(exactDef);
        registry.register(patternDef);

        List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.UserService");
        assertEquals(2, result.size());
    }

    @Test
    void namePatternDoesNotMatch_returnsEmpty() {
        InterceptorDefinition def = new InterceptorDefinition("pattern-interceptor",
                new Pointcut(ClassMatcher.byNamePattern("com\\.example\\..*Service"), MethodMatcher.byName("doWork")),
                new Interceptor() {});
        registry.register(def);

        List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.other.NotMatching");
        assertTrue(result.isEmpty());
    }

    @Test
    void noDuplicateWhenPatternMatchesSameClassAsIndex() {
        // Register a NAME_PATTERN that also matches the exact class name used in another definition
        InterceptorDefinition exactDef = new InterceptorDefinition("exact-interceptor",
                new Pointcut(ClassMatcher.byName("com.example.Svc"), MethodMatcher.byName("run")),
                new Interceptor() {});

        // This pattern also matches "com.example.Svc"
        InterceptorDefinition patternDef = new InterceptorDefinition("pattern-interceptor",
                new Pointcut(ClassMatcher.byNamePattern("com\\.example\\.Svc"), MethodMatcher.byName("run")),
                new Interceptor() {});

        registry.register(exactDef);
        registry.register(patternDef);

        List<InterceptorDefinition> result = registry.getInterceptorsForClass("com.example.Svc");
        assertEquals(2, result.size());
    }
}
