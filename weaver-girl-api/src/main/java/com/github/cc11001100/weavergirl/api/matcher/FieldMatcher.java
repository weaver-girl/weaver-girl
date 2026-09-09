package com.github.cc11001100.weavergirl.api.matcher;

import com.github.cc11001100.weavergirl.api.ValidationUtils;
import java.util.regex.Pattern;

/**
 * Matcher for selecting target fields to intercept within a matched class.
 *
 * <p>Supports matching by exact field name, regex name pattern, field type, annotation, or access modifiers (get/set).
 * Use the static factory methods to create matchers.
 *
 * <p>This class lives in the API module so plugin developers can construct matchers without depending on the core
 * implementation.
 *
 * <h3>MatchType semantics</h3>
 *
 * <ul>
 *   <li>{@link MatchType#EXACT_NAME} &mdash; matches the field name exactly
 *   <li>{@link MatchType#NAME_PATTERN} &mdash; matches the field name against a regex
 *   <li>{@link MatchType#TYPE} &mdash; matches fields of the specified fully-qualified type name
 *   <li>{@link MatchType#ANNOTATION} &mdash; matches fields annotated with the specified annotation (resolved by the core engine at class-load time)
 *   <li>{@link MatchType#GET} &mdash; matches field read access (getfield)
 *   <li>{@link MatchType#SET} &mdash; matches field write access (putfield)
 * </ul>
 *
 * <h3>Usage example</h3>
 *
 * <pre>
 * FieldMatcher exact     = FieldMatcher.byName("password");
 * FieldMatcher pattern   = FieldMatcher.byNamePattern(".*[Pp]assword.*");
 * FieldMatcher byType    = FieldMatcher.byType("java.lang.String");
 * FieldMatcher annotated = FieldMatcher.byAnnotation("com.example.Sensitive");
 * FieldMatcher get       = FieldMatcher.get();
 * FieldMatcher set       = FieldMatcher.set();</pre>
 *
 * @see MethodMatcher
 * @see com.github.cc11001100.weavergirl.api.pointcut.Pointcut
 * @since 1.7.0
 */
public class FieldMatcher {

  /**
   * Determines how a field is matched.
   *
   * <p>{@code EXACT_NAME}, {@code NAME_PATTERN}, {@code TYPE}, and {@code ANY} are evaluated by
   * {@link #matches(String, String)} directly. {@code ANNOTATION} is resolved by the core engine at
   * bytecode-instrumentation time.
   */
  public enum MatchType {
    /** Match by exact field name. */
    EXACT_NAME,
    /** Match by regex pattern against the field name. */
    NAME_PATTERN,
    /** Match by field type (fully-qualified class name). */
    TYPE,
    /** Match fields bearing a specific annotation. */
    ANNOTATION,
    /** Match field read access (getfield). */
    GET,
    /** Match field write access (putfield). */
    SET,
    /** Match any field. */
    ANY
  }

  private final MatchType matchType;
  private final String pattern;
  private final Pattern compiledRegex;

  private FieldMatcher(MatchType matchType, String pattern) {
    this.matchType = matchType;
    this.pattern = pattern;
    this.compiledRegex = (matchType == MatchType.NAME_PATTERN) ? Pattern.compile(pattern) : null;
  }

  /**
   * Creates a matcher that matches a field by its exact name.
   *
   * @param fieldName the field name (e.g., {@code "password"})
   * @return a new FieldMatcher with {@link MatchType#EXACT_NAME}
   * @throws IllegalArgumentException if {@code fieldName} is null or empty
   */
  public static FieldMatcher byName(String fieldName) {
    return new FieldMatcher(
        MatchType.EXACT_NAME, ValidationUtils.requireNonEmpty(fieldName, "fieldName"));
  }

  /**
   * Creates a matcher that matches a field name against a regular expression.
   *
   * @param regex a Java regex pattern (e.g., {@code ".*[Pp]assword.*"})
   * @return a new FieldMatcher with {@link MatchType#NAME_PATTERN}
   * @throws IllegalArgumentException if {@code regex} is null or empty
   */
  public static FieldMatcher byNamePattern(String regex) {
    return new FieldMatcher(
        MatchType.NAME_PATTERN, ValidationUtils.requireNonEmpty(regex, "regex"));
  }

  /**
   * Creates a matcher that matches fields by their exact type.
   *
   * @param typeName the fully-qualified type name (e.g., {@code "java.lang.String"})
   * @return a new FieldMatcher with {@link MatchType#TYPE}
   * @throws IllegalArgumentException if {@code typeName} is null or empty
   */
  public static FieldMatcher byType(String typeName) {
    return new FieldMatcher(
        MatchType.TYPE, ValidationUtils.requireNonEmpty(typeName, "typeName"));
  }

  /**
   * Creates a matcher that matches fields annotated with the specified annotation.
   *
   * <p>Annotation matching is resolved by the core engine at class-load time; it is not evaluated
   * by {@link #matches(String, String)}.
   *
   * @param annotationClassName the fully-qualified annotation class name (e.g., {@code
   *     "com.example.Sensitive"})
   * @return a new FieldMatcher with {@link MatchType#ANNOTATION}
   * @throws IllegalArgumentException if {@code annotationClassName} is null or empty
   */
  public static FieldMatcher byAnnotation(String annotationClassName) {
    return new FieldMatcher(
        MatchType.ANNOTATION,
        ValidationUtils.requireNonEmpty(annotationClassName, "annotationClassName"));
  }

  /**
   * Creates a matcher that matches field read access (getfield).
   *
   * @return a new FieldMatcher with {@link MatchType#GET}
   */
  public static FieldMatcher get() {
    return new FieldMatcher(MatchType.GET, "get");
  }

  /**
   * Creates a matcher that matches field write access (putfield).
   *
   * @return a new FieldMatcher with {@link MatchType#SET}
   */
  public static FieldMatcher set() {
    return new FieldMatcher(MatchType.SET, "set");
  }

  /**
   * Creates a matcher that matches any field.
   *
   * @return a new FieldMatcher with {@link MatchType#ANY}
   */
  public static FieldMatcher any() {
    return new FieldMatcher(MatchType.ANY, "*");
  }

  /**
   * Returns the match type of this matcher.
   *
   * @return the match type
   */
  public MatchType getMatchType() {
    return matchType;
  }

  /**
   * Returns the pattern string used for matching.
   *
   * @return the pattern string
   */
  public String getPattern() {
    return pattern;
  }

  /**
   * Tests whether the given field name and type match this matcher.
   *
   * <p>This method supports {@link MatchType#EXACT_NAME}, {@link MatchType#NAME_PATTERN}, {@link
   * MatchType#TYPE}, and {@link MatchType#ANY}. For {@link MatchType#ANNOTATION}, this method always
   * returns {@code false}; that is resolved by the core engine.
   *
   * @param fieldName the field name to test
   * @param typeName the field type name (fully-qualified), or null if unknown
   * @return true if the field matches
   */
  public boolean matches(String fieldName, String typeName) {
    switch (matchType) {
      case EXACT_NAME:
        return pattern.equals(fieldName);
      case NAME_PATTERN:
        return compiledRegex != null && compiledRegex.matcher(fieldName).matches();
      case TYPE:
        return pattern.equals(typeName);
      case GET:
      case SET:
      case ANY:
        return true;
      case ANNOTATION:
      default:
        return false;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof FieldMatcher)) return false;
    FieldMatcher that = (FieldMatcher) o;
    return pattern.equals(that.pattern) && matchType == that.matchType;
  }

  @Override
  public int hashCode() {
    return pattern.hashCode() * 31 + matchType.hashCode();
  }

  @Override
  public String toString() {
    return "FieldMatcher{" + matchType + ": " + pattern + "}";
  }
}
