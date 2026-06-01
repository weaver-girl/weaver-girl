package com.github.cc11001100.weavergirl.api.matcher;

import java.util.regex.Pattern;

/**
 * Matcher for selecting target methods to intercept within a matched class.
 * Supports matching by exact name, name pattern, annotation, or any method.
 */
public class MethodMatcher {

    public enum MatchType {
        EXACT_NAME,
        NAME_PATTERN,
        ANNOTATION,
        SIGNATURE,
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

    public static MethodMatcher byName(String methodName) {
        return new MethodMatcher(MatchType.EXACT_NAME, methodName);
    }

    public static MethodMatcher byNamePattern(String regex) {
        return new MethodMatcher(MatchType.NAME_PATTERN, regex);
    }

    public static MethodMatcher byAnnotation(String annotationClassName) {
        return new MethodMatcher(MatchType.ANNOTATION, annotationClassName);
    }

    /**
     * Match methods by name AND parameter types.
     * Parameter types are specified as comma-separated fully qualified class names.
     * Example: bySignature("process", "java.lang.String,int")
     */
    public static MethodMatcher bySignature(String methodName, String parameterTypes) {
        return new MethodMatcher(MatchType.SIGNATURE, methodName + "(" + parameterTypes + ")");
    }

    public static MethodMatcher any() {
        return new MethodMatcher(MatchType.ANY, "*");
    }

    public MatchType getMatchType() {
        return matchType;
    }

    public String getPattern() {
        return pattern;
    }

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
     * Match methods by name and parameter types.
     * For SIGNATURE match type, both name and parameter types are checked.
     * For all other match types, delegates to matches(methodName).
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
