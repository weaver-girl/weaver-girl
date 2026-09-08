package com.github.cc11001100.weavergirl.api;

import java.util.Objects;

/**
 * Utility methods for validating arguments passed to public API methods.
 *
 * <p>Centralizes null-checks and empty-checks so that every entry point produces consistent,
 * actionable error messages instead of raw {@link NullPointerException}s.
 *
 * <h3>Usage</h3>
 *
 * <pre>
 * ValidationUtils.requireNonNull(arg, "argName");
 * ValidationUtils.requireNonEmpty(arg, "argName");
 * </pre>
 *
 * @since 1.0.0
 */
public final class ValidationUtils {

  private ValidationUtils() {
    // utility class — not instantiable
  }

  /**
   * Validates that the given argument is not null.
   *
   * @param arg the argument to check
   * @param argName the parameter name (for the error message)
   * @param <T> the argument type
   * @return the argument, if non-null
   * @throws IllegalArgumentException if {@code arg} is null
   */
  public static <T> T requireNonNull(T arg, String argName) {
    Objects.requireNonNull(arg, () -> argName + " must not be null");
    return arg;
  }

  /**
   * Validates that the given string argument is neither null nor empty (after trimming).
   *
   * @param arg the string to check
   * @param argName the parameter name (for the error message)
   * @return the trimmed string, if non-empty
   * @throws IllegalArgumentException if {@code arg} is null or empty
   */
  public static String requireNonEmpty(String arg, String argName) {
    if (arg == null) {
      throw new IllegalArgumentException(argName + " must not be null");
    }
    String trimmed = arg.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(argName + " must not be empty");
    }
    return trimmed;
  }

  /**
   * Validates that the given string argument is neither null nor empty. Unlike {@link
   * #requireNonEmpty(String, String)}, this does NOT trim the input.
   *
   * @param arg the string to check
   * @param argName the parameter name (for the error message)
   * @return the original string, if non-empty
   * @throws IllegalArgumentException if {@code arg} is null or empty
   */
  public static String requireNonBlank(String arg, String argName) {
    if (arg == null) {
      throw new IllegalArgumentException(argName + " must not be null");
    }
    if (arg.isEmpty()) {
      throw new IllegalArgumentException(argName + " must not be empty");
    }
    return arg;
  }

  /**
   * Validates that the given integer argument is positive (&gt; 0).
   *
   * @param value the value to check
   * @param argName the parameter name (for the error message)
   * @return the value, if positive
   * @throws IllegalArgumentException if {@code value} is not positive
   */
  public static int requirePositive(int value, String argName) {
    if (value <= 0) {
      throw new IllegalArgumentException(argName + " must be positive, got: " + value);
    }
    return value;
  }

  /**
   * Validates that the given long argument is non-negative (&ge; 0).
   *
   * @param value the value to check
   * @param argName the parameter name (for the error message)
   * @return the value, if non-negative
   * @throws IllegalArgumentException if {@code value} is negative
   */
  public static long requireNonNegative(long value, String argName) {
    if (value < 0) {
      throw new IllegalArgumentException(argName + " must be non-negative, got: " + value);
    }
    return value;
  }

  /**
   * Validates that the given argument is within the specified range (inclusive).
   *
   * @param value the value to check
   * @param min the minimum allowed value (inclusive)
   * @param max the maximum allowed value (inclusive)
   * @param argName the parameter name (for the error message)
   * @return the value, if within range
   * @throws IllegalArgumentException if {@code value} is outside [min, max]
   */
  public static int requireInRange(int value, int min, int max, String argName) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          argName + " must be between " + min + " and " + max + ", got: " + value);
    }
    return value;
  }
}
