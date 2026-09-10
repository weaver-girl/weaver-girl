package com.github.cc11001100.weavergirl.api.matcher;

import com.github.cc11001100.weavergirl.api.ValidationUtils;
import java.util.regex.Pattern;

/**
 * Matcher for selecting target classes to intercept.
 *
 * <p>Supports matching by exact fully-qualified name, regex name pattern, annotation, superclass,
 * or implemented interface. Use the static factory methods to create matchers.
 *
 * <p>This class lives in the API module so plugin developers can construct matchers without
 * depending on the core implementation.
 *
 * <h3>MatchType semantics</h3>
 *
 * <ul>
 *   <li>{@link MatchType#EXACT_NAME} &mdash; matches the fully-qualified class name exactly
 *   <li>{@link MatchType#NAME_PATTERN} &mdash; matches the fully-qualified class name against a
 *       regex
 *   <li>{@link MatchType#ANNOTATION} &mdash; matches classes annotated with the specified
 *       annotation (resolved by the core engine at class-load time)
 *   <li>{@link MatchType#SUPER_CLASS} &mdash; matches classes that extend the specified superclass
 *       (resolved by the core engine at class-load time)
 *   <li>{@link MatchType#INTERFACE} &mdash; matches classes that implement the specified interface
 *       (resolved by the core engine at class-load time)
 * </ul>
 *
 * <h3>Usage example</h3>
 *
 * <pre>
 * ClassMatcher exact    = ClassMatcher.byName("com.example.service.UserService");
 * ClassMatcher pattern  = ClassMatcher.byNamePattern("com\\.example\\..*Service");
 * ClassMatcher annotated = ClassMatcher.byAnnotation("com.example.Trace");
 * ClassMatcher subclass = ClassMatcher.bySuperClass("com.example.BaseService");
 * ClassMarker iface     = ClassMatcher.byInterface("java.io.Serializable");</pre>
 *
 * @see MethodMatcher
 * @see com.github.cc11001100.weavergirl.api.pointcut.Pointcut
 * @since 1.0.0
 */
public class ClassMatcher {

  /**
   * Determines how a class name or metadata is matched.
   *
   * <p>{@code EXACT_NAME} and {@code NAME_PATTERN} are evaluated by {@link #matches(String)}
   * directly. The remaining types ({@code ANNOTATION}, {@code SUPER_CLASS}, {@code INTERFACE}) are
   * resolved by the core engine at bytecode-instrumentation time and cannot be evaluated by {@link
   * #matches(String)} alone.
   */
  public enum MatchType {
    /** Match by exact fully-qualified class name. */
    EXACT_NAME,
    /** Match by regex pattern against the fully-qualified class name. */
    NAME_PATTERN,
    /** Match any class. */
    ANY,
    /** Match classes bearing a specific annotation. */
    ANNOTATION,
    /** Match classes that extend a specific superclass. */
    SUPER_CLASS,
    /** Match classes that implement a specific interface. */
    INTERFACE
  }

  private final MatchType matchType;
  private final String pattern;
  private final Pattern compiledRegex;

  private ClassMatcher(MatchType matchType, String pattern) {
    this.matchType = matchType;
    this.pattern = pattern;
    this.compiledRegex = (matchType == MatchType.NAME_PATTERN) ? Pattern.compile(pattern) : null;
  }

  /**
   * Creates a matcher that matches a class by its exact fully-qualified name.
   *
   * @param className the fully-qualified class name (e.g., {@code "com.example.Service"})
   * @return a new ClassMatcher with {@link MatchType#EXACT_NAME}
   * @throws IllegalArgumentException if {@code className} is null or empty
   */
  public static ClassMatcher byName(String className) {
    return new ClassMatcher(
        MatchType.EXACT_NAME, ValidationUtils.requireNonEmpty(className, "className"));
  }

  /**
   * Creates a matcher that matches a class name against a regular expression.
   *
   * @param regex a Java regex pattern (e.g., {@code "com\\.example\\..*Service"})
   * @return a new ClassMatcher with {@link MatchType#NAME_PATTERN}
   * @throws IllegalArgumentException if {@code regex} is null or empty
   */
  public static ClassMatcher byNamePattern(String regex) {
    return new ClassMatcher(
        MatchType.NAME_PATTERN, ValidationUtils.requireNonEmpty(regex, "regex"));
  }

  /**
   * Creates a matcher that matches classes annotated with the specified annotation.
   *
   * <p>Annotation matching is resolved by the core engine at class-load time; it is not evaluated
   * by {@link #matches(String)}.
   *
   * @param annotationClassName the fully-qualified annotation class name (e.g., {@code
   *     "com.example.Trace"})
   * @return a new ClassMatcher with {@link MatchType#ANNOTATION}
   * @throws IllegalArgumentException if {@code annotationClassName} is null or empty
   */
  public static ClassMatcher byAnnotation(String annotationClassName) {
    return new ClassMatcher(
        MatchType.ANNOTATION,
        ValidationUtils.requireNonEmpty(annotationClassName, "annotationClassName"));
  }

  /**
   * Creates a matcher that matches classes that extend the specified superclass.
   *
   * <p>Superclass matching is resolved by the core engine at class-load time; it is not evaluated
   * by {@link #matches(String)}.
   *
   * @param superClassName the fully-qualified superclass name (e.g., {@code
   *     "com.example.BaseService"})
   * @return a new ClassMatcher with {@link MatchType#SUPER_CLASS}
   * @throws IllegalArgumentException if {@code superClassName} is null or empty
   */
  public static ClassMatcher bySuperClass(String superClassName) {
    return new ClassMatcher(
        MatchType.SUPER_CLASS, ValidationUtils.requireNonEmpty(superClassName, "superClassName"));
  }

  /**
   * Creates a matcher that matches classes that implement the specified interface.
   *
   * <p>Interface matching is resolved by the core engine at class-load time; it is not evaluated by
   * {@link #matches(String)}.
   *
   * @param interfaceName the fully-qualified interface name (e.g., {@code "java.io.Serializable"})
   * @return a new ClassMatcher with {@link MatchType#INTERFACE}
   * @throws IllegalArgumentException if {@code interfaceName} is null or empty
   */
  public static ClassMatcher byInterface(String interfaceName) {
    return new ClassMatcher(
        MatchType.INTERFACE, ValidationUtils.requireNonEmpty(interfaceName, "interfaceName"));
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
   * <p>For {@link MatchType#EXACT_NAME}, this is the exact class name. For {@link
   * MatchType#NAME_PATTERN}, this is the regex pattern. For {@link MatchType#ANNOTATION}, {@link
   * MatchType#SUPER_CLASS}, and {@link MatchType#INTERFACE}, this is the fully-qualified name of
   * the annotation, superclass, or interface respectively.
   *
   * @return the pattern string
   */
  public String getPattern() {
    return pattern;
  }

  /**
   * Creates a matcher that matches every class.
   *
   * @return a new ClassMatcher with {@link MatchType#ANY}
   * @since 1.7.0
   */
  public static ClassMatcher any() {
    return new ClassMatcher(MatchType.ANY, "*");
  }

  /**
   * Tests whether the given class name matches this matcher.
   *
   * <p>This method supports {@link MatchType#EXACT_NAME} and {@link MatchType#NAME_PATTERN} and
   * {@link MatchType#ANY}. For {@code ANNOTATION}, {@code SUPER_CLASS}, and {@code INTERFACE} match
   * types, this method always returns {@code false}; those are resolved by the core engine at
   * bytecode-instrumentation time.
   *
   * @param className the fully-qualified class name to test
   * @return true if the class name matches
   */
  public boolean matches(String className) {
    switch (matchType) {
      case EXACT_NAME:
        return pattern.equals(className);
      case NAME_PATTERN:
        return compiledRegex != null && compiledRegex.matcher(className).matches();
      case ANY:
        return true;
      case ANNOTATION:
        // Match when the tested class IS the annotation class, or when the
        // annotation class pattern equals the tested class name. This allows
        // @target() pointcuts to be validated at the API level against the
        // annotation class name itself.
        return pattern.equals(className);
      case SUPER_CLASS:
      case INTERFACE:
        return false;
      default:
        return false;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ClassMatcher)) return false;
    ClassMatcher that = (ClassMatcher) o;
    return pattern.equals(that.pattern) && matchType == that.matchType;
  }

  @Override
  public int hashCode() {
    return pattern.hashCode() * 31 + matchType.hashCode();
  }

  @Override
  public String toString() {
    return "ClassMatcher{" + matchType + ": " + pattern + "}";
  }
}
