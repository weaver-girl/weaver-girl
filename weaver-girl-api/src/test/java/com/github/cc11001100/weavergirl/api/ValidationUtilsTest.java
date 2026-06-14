package com.github.cc11001100.weavergirl.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ValidationUtils}.
 */
class ValidationUtilsTest {

    // --- requireNonNull ---

    @Test
    void requireNonNull_returnsValueWhenNotNull() {
        String result = ValidationUtils.requireNonNull("hello", "arg");
        assertEquals("hello", result);
    }

    @Test
    void requireNonNull_throwsForNull() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> ValidationUtils.requireNonNull(null, "myArg"));
        assertTrue(ex.getMessage().contains("myArg"));
        assertTrue(ex.getMessage().contains("must not be null"));
    }

    // --- requireNonEmpty ---

    @Test
    void requireNonEmpty_returnsTrimmedValueWhenNonEmpty() {
        assertEquals("hello", ValidationUtils.requireNonEmpty("hello", "arg"));
        assertEquals("hello", ValidationUtils.requireNonEmpty("  hello  ", "arg"));
    }

    @Test
    void requireNonEmpty_throwsForNull() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.requireNonEmpty(null, "arg"));
        assertTrue(ex.getMessage().contains("must not be null"));
    }

    @Test
    void requireNonEmpty_throwsForEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.requireNonEmpty("", "arg"));
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.requireNonEmpty("   ", "arg"));
    }

    // --- requireNonBlank ---

    @Test
    void requireNonBlank_returnsValueWhenNonBlank() {
        assertEquals("hello", ValidationUtils.requireNonBlank("hello", "arg"));
        assertEquals("  hello  ", ValidationUtils.requireNonBlank("  hello  ", "arg"));
    }

    @Test
    void requireNonBlank_throwsForNull() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.requireNonBlank(null, "arg"));
    }

    @Test
    void requireNonBlank_throwsForEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.requireNonBlank("", "arg"));
    }

    // --- requirePositive ---

    @Test
    void requirePositive_returnsValueWhenPositive() {
        assertEquals(1, ValidationUtils.requirePositive(1, "arg"));
        assertEquals(100, ValidationUtils.requirePositive(100, "arg"));
    }

    @Test
    void requirePositive_throwsForZero() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.requirePositive(0, "arg"));
    }

    @Test
    void requirePositive_throwsForNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.requirePositive(-1, "arg"));
    }

    // --- requireNonNegative ---

    @Test
    void requireNonNegative_returnsValueWhenNonNegative() {
        assertEquals(0, ValidationUtils.requireNonNegative(0, "arg"));
        assertEquals(42, ValidationUtils.requireNonNegative(42, "arg"));
    }

    @Test
    void requireNonNegative_throwsForNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.requireNonNegative(-1, "arg"));
    }

    // --- requireInRange ---

    @Test
    void requireInRange_returnsValueWhenInRange() {
        assertEquals(0, ValidationUtils.requireInRange(0, 0, 10, "arg"));
        assertEquals(10, ValidationUtils.requireInRange(10, 0, 10, "arg"));
        assertEquals(5, ValidationUtils.requireInRange(5, 0, 10, "arg"));
    }

    @Test
    void requireInRange_throwsForBelowMin() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.requireInRange(-1, 0, 10, "arg"));
    }

    @Test
    void requireInRange_throwsForAboveMax() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationUtils.requireInRange(11, 0, 10, "arg"));
    }
}
