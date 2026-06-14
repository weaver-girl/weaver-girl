package com.github.cc11001100.weavergirl.api.matcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MethodMatcherTest {

    @Test
    void byName_matchesExact() {
        MethodMatcher matcher = MethodMatcher.byName("greet");
        assertTrue(matcher.matches("greet"));
    }

    @Test
    void byName_doesNotMatchDifferent() {
        MethodMatcher matcher = MethodMatcher.byName("greet");
        assertFalse(matcher.matches("hello"));
    }

    @Test
    void byName_returnsTrueForExactMatch() {
        assertTrue(MethodMatcher.byName("greet").matches("greet"));
    }

    @Test
    void byName_returnsFalseForNonMatch() {
        assertFalse(MethodMatcher.byName("greet").matches("hello"));
    }

    @Test
    void any_matchesEverything() {
        MethodMatcher matcher = MethodMatcher.any();
        assertTrue(matcher.matches("greet"));
        assertTrue(matcher.matches("hello"));
        assertTrue(matcher.matches("anything"));
        assertTrue(matcher.matches(""));
    }

    @Test
    void byNamePattern_matchesPattern() {
        MethodMatcher matcher = MethodMatcher.byNamePattern("get.*");
        assertTrue(matcher.matches("getName"));
        assertTrue(matcher.matches("getAge"));
        assertFalse(matcher.matches("setAge"));
    }

    @Test
    void byNamePattern_matchesDigits() {
        MethodMatcher matcher = MethodMatcher.byNamePattern("process\\d+");
        assertTrue(matcher.matches("process1"));
        assertTrue(matcher.matches("process2"));
        assertFalse(matcher.matches("process"));
    }

    @Test
    void byAnnotation_hasCorrectMatchType() {
        MethodMatcher matcher = MethodMatcher.byAnnotation("com.example.MyAnnotation");
        assertEquals(MethodMatcher.MatchType.ANNOTATION, matcher.getMatchType());
    }

    @Test
    void byName_hasCorrectMatchType() {
        assertEquals(MethodMatcher.MatchType.EXACT_NAME, MethodMatcher.byName("foo").getMatchType());
    }

    @Test
    void byNamePattern_hasCorrectMatchType() {
        assertEquals(MethodMatcher.MatchType.NAME_PATTERN, MethodMatcher.byNamePattern(".*").getMatchType());
    }

    @Test
    void any_hasCorrectMatchType() {
        assertEquals(MethodMatcher.MatchType.ANY, MethodMatcher.any().getMatchType());
    }

    @Test
    void getPattern_returnsCorrectPattern() {
        assertEquals("greet", MethodMatcher.byName("greet").getPattern());
        assertEquals("get.*", MethodMatcher.byNamePattern("get.*").getPattern());
        assertEquals("com.example.MyAnnotation", MethodMatcher.byAnnotation("com.example.MyAnnotation").getPattern());
        assertEquals("*", MethodMatcher.any().getPattern());
    }
}
