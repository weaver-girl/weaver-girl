package com.github.cc11001100.weavergirl.core.config.validation;

import java.util.*;

/**
 * Result of configuration validation.
 *
 * @since 1.2.0
 */
public class ConfigValidationResult {

  private final List<String> errors;
  private final List<String> warnings;

  public ConfigValidationResult(List<String> errors, List<String> warnings) {
    this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
  }

  /** Whether the configuration is valid (no errors). */
  public boolean isValid() {
    return errors.isEmpty();
  }

  /** Validation errors (blocking). */
  public List<String> getErrors() {
    return errors;
  }

  /** Validation warnings (non-blocking). */
  public List<String> getWarnings() {
    return warnings;
  }

  /** Whether there are any warnings. */
  public boolean hasWarnings() {
    return !warnings.isEmpty();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    if (!errors.isEmpty()) {
      sb.append("ERRORS:\n");
      for (String e : errors) sb.append("  ❌ ").append(e).append("\n");
    }
    if (!warnings.isEmpty()) {
      sb.append("WARNINGS:\n");
      for (String w : warnings) sb.append("  ⚠️ ").append(w).append("\n");
    }
    if (errors.isEmpty() && warnings.isEmpty()) {
      sb.append("✅ Configuration is valid");
    }
    return sb.toString();
  }

  public static ConfigValidationResult ok() {
    return new ConfigValidationResult(Collections.emptyList(), Collections.emptyList());
  }

  public static ConfigValidationResult error(String message) {
    return new ConfigValidationResult(Collections.singletonList(message), Collections.emptyList());
  }

  public static ConfigValidationResult of(List<String> errors, List<String> warnings) {
    return new ConfigValidationResult(errors, warnings);
  }
}
