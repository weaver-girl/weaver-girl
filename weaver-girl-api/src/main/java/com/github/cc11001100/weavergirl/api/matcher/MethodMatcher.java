package com.github.cc11001100.weavergirl.api.matcher;

import java.util.regex.Pattern;

/**
 * Matcher for selecting target methods to intercept within a matched class.
 *
 * <p>Supports matching by exact method name, regex name pattern, annotation, full
 * signature (name + parameter types), or a wildcard that matches any method.
 * Use the static factory methods to create matchers.</p>
 *
 * <h3>MatchType semantics</h3>
 * <ul>
 *   <li>{@link MatchType#EXACT_NAME} &mdash; matches the method name exactly</li>
 *   <li>{@link MatchType#NAME_PATTERN} &mdash; matches the method name against a regex</li>
 *   <li>{@link MatchType#ANNOTATION} &mdash; matches methods annotated with the specified annotation
 *       (resolved by the core engine at class-load time)</li>
 *   <li>{@link MatchType#SIGNATURE} &mdash; matches by method name and parameter types</li>
 *   <li>{@link MatchType#ANY} &mdash; matches all methods</li>
 * </ul>
 *
 * <h3>Usage example</h3>
 * <pre>
 * MethodMatcher exact     = MethodMatcher.byName("process");
 * MethodMatcher pattern   = MethodMatcher.byNamePattern("process.*");
 * MethodMatcher annotated = MethodMatcher.byAnnotation("com.example.Traced");
 * MethodMatcher sig       = MethodMatcher.bySignature("process", "java.lang.String,int");
 * MethodMatcher all       = MethodMatcher.any();</pre>
 *
 * @see ClassMatcher
 * @see com.github.cc11001100.weavergirl.api.pointcut.Pointcut
 * @since 1.0.0
 */
public class MethodMatcher {

    /**
     * Determines how a method name or metadata is matched.
     *
     * <p>{@code EXACT_NAME}, {@code NAME_PATTERN}, {@code SIGNATURE}, and {@code ANY}
     * are evaluated by {@link #matches(String)} or
     * {@link #matches(String, Class[])}. {@code ANNOTATION} is resolved by the
     * core engine at bytecode-instrumentation time.</p>
     */
    public enum MatchType {
        /** Match by exact method name. */
        EXACT_NAME,
        /** Match by regex pattern against the method name. */
        NAME_PATTERN,
        /** Match methods bearing a specific annotation. */
        ANNOTATION,
        /** Match by method name and parameter types (signature). */
        SIGNATURE,
        /** Match all methods. */
        ANY
    }

    private final MatchType matchType;
    private final String pattern;
    private final Pattern compiledRegex;

    private MethodMatcher(MatchType matchType, String pattern) {
        this.matchType = matchType;
        this.pattern = pattern;
        this.compiledRegex = (matchType == MatchType.NAME_PATTERN)
                ? Pattern.compile(pattern) : null;
    }

    /**
     * Creates a matcher that matches a method by its exact name.
     *
     * @param methodName the method name (e.g., {@code "process"})
     * @return a new MethodMatcher with {@link MatchType#EXACT_NAME}
     */
    public static MethodMatcher byName(String methodName) {
        return new MethodMatcher(MatchType.EXACT_NAME, methodName);
    }

    /**
     * Creates a matcher that matches a method name against a regular expression.
     *
     * @param regex a Java regex pattern (e.g., {@code "process.*"})
     * @return a new MethodMatcher with {@link MatchType#NAME_PATTERN}
     */
    public static MethodMatcher byNamePattern(String regex) {
        return new MethodMatcher(MatchType.NAME_PATTERN, regex);
    }

    /**
     * Creates a matcher that matches methods annotated with the specified annotation.
     *
     * <p>Annotation matching is resolved by the core engine at class-load time;
     * it is not evaluated by {@link #matches(String)}.</p>
     *
     * @param annotationClassName the fully-qualified annotation class name
     *                            (e.g., {@code "com.example.Traced"})
     * @return a new MethodMatcher with {@link MatchType#ANNOTATION}
     */
    public static MethodMatcher byAnnotation(String annotationClassName) {
        return new MethodMatcher(MatchType.ANNOTATION, annotationClassName);
    }

    /**
     * Creates a matcher that matches methods by name and parameter types (signature).
     *
     * <p>Parameter types are specified as a comma-separated string of fully-qualified
     * class names. For example, {@code bySignature("process", "java.lang.String,int")}
     * matches a method named {@code process} that takes a {@code String} and an
     * {@code int}.</p>
     *
     * @param methodName      the method name
     * @param parameterTypes  comma-separated fully-qualified parameter type names
     *                        (e.g., {@code "java.lang.String,int"})
     * @return a new MethodMatcher with {@link MatchType#SIGNATURE}
     */
    public static MethodMatcher bySignature(String methodName, String parameterTypes) {
        return new MethodMatcher(MatchType.SIGNATURE, methodName + "(" + parameterTypes + ")");
    }

    /**
     * Creates a matcher that matches any method.
     *
     * @return a new MethodMatcher with {@link MatchType#ANY}
     */
    public static MethodMatcher any() {
        return new MethodMatcher(MatchType.ANY, "*");
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
     * <p>For {@link MatchType#SIGNATURE}, the pattern is in the form
     * {@code methodName(param1,param2)}.</p>
     *
     * @return the pattern string
     */
    public String getPattern() {
        return pattern;
    }

    /**
     * Tests whether the given method name matches this matcher.
     *
     * <p>This method supports {@link MatchType#EXACT_NAME}, {@link MatchType#NAME_PATTERN},
     * {@link MatchType#SIGNATURE} (name part only), and {@link MatchType#ANY}.
     * For {@link MatchType#ANNOTATION}, this method always returns {@code false}.</p>
     *
     * <p>For {@code SIGNATURE} matchers, only the method name portion of the signature
     * is compared. Use {@link #matches(String, Class[])} for full signature matching.</p>
     *
     * @param methodName the method name to test
     * @return true if the method name matches
     */
    public boolean matches(String methodName) {
        switch (matchType) {
            case EXACT_NAME:
                return pattern.equals(methodName);
            case NAME_PATTERN:
                return compiledRegex != null && compiledRegex.matcher(methodName).matches();
            case ANY:
                return true;
            case SIGNATURE:
                int parenIdx = pattern.indexOf('(');
                if (parenIdx < 0) return false;
                String patternName = pattern.substring(0, parenIdx);
                return patternName.equals(methodName);
            default:
                return false;
        }
    }

    /**
     * Tests whether the given method name and parameter types match this matcher.
     *
     * <p>For {@link MatchType#SIGNATURE}, both the method name and parameter types are
     * checked. Parameter types are compared by their fully-qualified names against the
     * types encoded in the signature pattern.</p>
     *
     * <p>For all other match types, this delegates to {@link #matches(String)}.</p>
     *
     * @param methodName     the method name to test
     * @param parameterTypes the parameter types of the method, or null for no parameters
     * @return true if the method signature matches
     */
    public boolean matches(String methodName, Class<?>[] parameterTypes) {
        if (matchType == MatchType.SIGNATURE) {
            // Parse "methodName(param1,param2)" from pattern
            int parenIdx = pattern.indexOf('(');
            if (parenIdx < 0) return false;
            String patternName = pattern.substring(0, parenIdx);
            if (!patternName.equals(methodName)) return false;
            String patternParams = pattern.substring(parenIdx + 1, pattern.length() - 1);
            if (patternParams.isEmpty()) {
                return parameterTypes == null || parameterTypes.length == 0;
            }
            String[] expectedTypes = patternParams.split(",");
            if (parameterTypes == null || parameterTypes.length != expectedTypes.length) return false;
            for (int i = 0; i < expectedTypes.length; i++) {
                if (!expectedTypes[i].trim().equals(parameterTypes[i].getName())) return false;
            }
            return true;
        }
        return matches(methodName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MethodMatcher)) return false;
        MethodMatcher that = (MethodMatcher) o;
        return pattern.equals(that.pattern) && matchType == that.matchType;
    }

    @Override
    public int hashCode() {
        return pattern.hashCode() * 31 + matchType.hashCode();
    }

    @Override
    public String toString() {
        return "MethodMatcher{" + matchType + ": " + pattern + "}";
    }
}
