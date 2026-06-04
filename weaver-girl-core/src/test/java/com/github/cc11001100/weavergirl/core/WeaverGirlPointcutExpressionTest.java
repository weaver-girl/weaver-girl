package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WeaverGirl#interceptExpression(String)} and
 * {@link WeaverGirl.ExpressionInterceptBuilder}.
 */
class WeaverGirlPointcutExpressionTest {

    @Test
    void interceptExpression_execution_exactClass() {
        WeaverGirl wg = WeaverGirl.create();
        wg.interceptExpression("execution(* com.example.MyService.doWork(..))")
                .before(inv -> {})
                .install();

        List<InterceptorDefinition> defs = wg.getRegistry().getAllDefinitions();
        assertEquals(1, defs.size());

        InterceptorDefinition def = defs.get(0);
        ClassMatcher classMatcher = def.getPointcut().getClassMatcher();
        assertEquals(ClassMatcher.MatchType.EXACT_NAME, classMatcher.getMatchType());
        assertEquals("com.example.MyService", classMatcher.getPattern());
        assertTrue(def.getName().startsWith("expr-"));
    }

    @Test
    void interceptExpression_execution_patternClass() {
        WeaverGirl wg = WeaverGirl.create();
        wg.interceptExpression("execution(* com.example..*Service.process(..))")
                .before(inv -> {})
                .install();

        List<InterceptorDefinition> defs = wg.getRegistry().getAllDefinitions();
        assertEquals(1, defs.size());

        InterceptorDefinition def = defs.get(0);
        ClassMatcher classMatcher = def.getPointcut().getClassMatcher();
        assertEquals(ClassMatcher.MatchType.NAME_PATTERN, classMatcher.getMatchType());
        // The wildcard pattern "com.example..*Service" should match class names like "com.example.foo.BarService"
        assertTrue(classMatcher.matches("com.example.foo.BarService"));
        assertFalse(classMatcher.matches("com.other.Service"));
    }

    @Test
    void interceptExpression_atWithin() {
        WeaverGirl wg = WeaverGirl.create();
        wg.interceptExpression("@within(com.example.Trace)")
                .before(inv -> {})
                .install();

        List<InterceptorDefinition> defs = wg.getRegistry().getAllDefinitions();
        assertEquals(1, defs.size());

        InterceptorDefinition def = defs.get(0);
        ClassMatcher classMatcher = def.getPointcut().getClassMatcher();
        assertEquals(ClassMatcher.MatchType.ANNOTATION, classMatcher.getMatchType());
        assertEquals("com.example.Trace", classMatcher.getPattern());
    }

    @Test
    void interceptExpression_subclassOf() {
        WeaverGirl wg = WeaverGirl.create();
        wg.interceptExpression("subclassOf(com.example.BaseService)")
                .before(inv -> {})
                .install();

        List<InterceptorDefinition> defs = wg.getRegistry().getAllDefinitions();
        assertEquals(1, defs.size());

        InterceptorDefinition def = defs.get(0);
        ClassMatcher classMatcher = def.getPointcut().getClassMatcher();
        assertEquals(ClassMatcher.MatchType.SUPER_CLASS, classMatcher.getMatchType());
        assertEquals("com.example.BaseService", classMatcher.getPattern());
    }

    @Test
    void interceptExpression_withPriority() {
        WeaverGirl wg = WeaverGirl.create();
        wg.interceptExpression("execution(* com.example.Service.run(..))")
                .before(inv -> {})
                .priority(10)
                .install();

        List<InterceptorDefinition> defs = wg.getRegistry().getAllDefinitions();
        assertEquals(1, defs.size());

        InterceptorDefinition def = defs.get(0);
        assertEquals(10, def.getPriority());
    }
}
