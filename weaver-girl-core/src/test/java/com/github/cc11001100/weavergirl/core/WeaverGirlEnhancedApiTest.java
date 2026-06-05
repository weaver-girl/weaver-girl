package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WeaverGirlEnhancedApiTest {

    @Test
    void interceptClassPattern_createsPatternClassMatcher() {
        WeaverGirl wg = WeaverGirl.create();
        wg.interceptClassPattern("com\\.example\\..*Service")
                .method("process")
                .before(inv -> {})
                .install();

        List<InterceptorDefinition> defs = wg.getRegistry().getAllDefinitions();
        assertEquals(1, defs.size());
        assertEquals(ClassMatcher.MatchType.NAME_PATTERN, defs.get(0).getPointcut().getClassMatcher().getMatchType());
        assertTrue(defs.get(0).getPointcut().getClassMatcher().matches("com.example.UserService"));
        assertFalse(defs.get(0).getPointcut().getClassMatcher().matches("com.other.Service"));
    }

    @Test
    void interceptAnnotated_createsAnnotationClassMatcher() {
        WeaverGirl wg = WeaverGirl.create();
        wg.interceptAnnotated("com.example.Monitored")
                .anyMethod()
                .before(inv -> {})
                .install();

        List<InterceptorDefinition> defs = wg.getRegistry().getAllDefinitions();
        assertEquals(1, defs.size());
        assertEquals(ClassMatcher.MatchType.ANNOTATION, defs.get(0).getPointcut().getClassMatcher().getMatchType());
        assertEquals("com.example.Monitored", defs.get(0).getPointcut().getClassMatcher().getPattern());
    }

    @Test
    void interceptSubclassOf_createsSuperClassMatcher() {
        WeaverGirl wg = WeaverGirl.create();
        wg.interceptSubclassOf("com.example.BaseService")
                .method("execute")
                .after(inv -> {})
                .install();

        List<InterceptorDefinition> defs = wg.getRegistry().getAllDefinitions();
        assertEquals(1, defs.size());
        assertEquals(ClassMatcher.MatchType.SUPER_CLASS, defs.get(0).getPointcut().getClassMatcher().getMatchType());
        assertEquals("com.example.BaseService", defs.get(0).getPointcut().getClassMatcher().getPattern());
    }

    @Test
    void interceptImplementing_createsInterfaceMatcher() {
        WeaverGirl wg = WeaverGirl.create();
        wg.interceptImplementing("java.io.Serializable")
                .anyMethod()
                .before(inv -> {})
                .install();

        List<InterceptorDefinition> defs = wg.getRegistry().getAllDefinitions();
        assertEquals(1, defs.size());
        assertEquals(ClassMatcher.MatchType.INTERFACE, defs.get(0).getPointcut().getClassMatcher().getMatchType());
        assertEquals("java.io.Serializable", defs.get(0).getPointcut().getClassMatcher().getPattern());
    }

    @Test
    void methodAnnotated_createsAnnotationMethodMatcher() {
        WeaverGirl wg = WeaverGirl.create();
        wg.intercept("com.example.Service")
                .methodAnnotated("com.example.Traced")
                .before(inv -> {})
                .install();

        List<InterceptorDefinition> defs = wg.getRegistry().getAllDefinitions();
        assertEquals(1, defs.size());
        assertEquals(MethodMatcher.MatchType.ANNOTATION, defs.get(0).getPointcut().getMethodMatcher().getMatchType());
        assertEquals("com.example.Traced", defs.get(0).getPointcut().getMethodMatcher().getPattern());
    }

    @Test
    void methodPattern_createsPatternMethodMatcher() {
        WeaverGirl wg = WeaverGirl.create();
        wg.intercept("com.example.Service")
                .methodPattern("process.*")
                .before(inv -> {})
                .install();

        List<InterceptorDefinition> defs = wg.getRegistry().getAllDefinitions();
        assertEquals(1, defs.size());
        assertEquals(MethodMatcher.MatchType.NAME_PATTERN, defs.get(0).getPointcut().getMethodMatcher().getMatchType());
    }

    @Test
    void combined_classAnnotationAndMethodAnnotation() {
        WeaverGirl wg = WeaverGirl.create();
        wg.interceptAnnotated("com.example.Monitored")
                .methodAnnotated("com.example.Traced")
                .before(inv -> {})
                .priority(5)
                .install();

        List<InterceptorDefinition> defs = wg.getRegistry().getAllDefinitions();
        assertEquals(1, defs.size());
        assertEquals(ClassMatcher.MatchType.ANNOTATION, defs.get(0).getPointcut().getClassMatcher().getMatchType());
        assertEquals(MethodMatcher.MatchType.ANNOTATION, defs.get(0).getPointcut().getMethodMatcher().getMatchType());
        assertEquals(5, defs.get(0).getPriority());
    }

    @Test
    void legacyIntercept_exactClassAndMethod_stillWorks() {
        WeaverGirl wg = WeaverGirl.create();
        wg.intercept("com.example.Service")
                .method("process")
                .before(inv -> {})
                .install();

        List<InterceptorDefinition> defs = wg.getRegistry().getAllDefinitions();
        assertEquals(1, defs.size());
        assertEquals(ClassMatcher.MatchType.EXACT_NAME, defs.get(0).getPointcut().getClassMatcher().getMatchType());
        assertEquals("com.example.Service", defs.get(0).getPointcut().getClassMatcher().getPattern());
        assertEquals(MethodMatcher.MatchType.EXACT_NAME, defs.get(0).getPointcut().getMethodMatcher().getMatchType());
        assertEquals("process", defs.get(0).getPointcut().getMethodMatcher().getPattern());
    }
}
