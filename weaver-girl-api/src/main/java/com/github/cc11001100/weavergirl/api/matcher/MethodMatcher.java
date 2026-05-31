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
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return "MethodMatcher{" + matchType + ": " + pattern + "}";
    }
}
