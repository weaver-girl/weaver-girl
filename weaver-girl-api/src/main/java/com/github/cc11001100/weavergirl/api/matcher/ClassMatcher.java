package com.github.cc11001100.weavergirl.api.matcher;

import java.util.regex.Pattern;

/**
 * Matcher for selecting target classes to intercept.
 * Supports matching by exact name, name pattern, annotation, or parent class.
 *
 * <p>This class lives in the API module so plugin developers can construct
 * matchers without depending on the core implementation.</p>
 */
public class ClassMatcher {

    public enum MatchType {
        EXACT_NAME,
        NAME_PATTERN,
        ANNOTATION,
        SUPER_CLASS,
        INTERFACE
    }

    private final MatchType matchType;
    private final String pattern;
    private final Pattern compiledRegex;

    private ClassMatcher(MatchType matchType, String pattern) {
        this.matchType = matchType;
        this.pattern = pattern;
        this.compiledRegex = (matchType == MatchType.NAME_PATTERN)
                ? Pattern.compile(pattern) : null;
    }

    public static ClassMatcher byName(String className) {
        return new ClassMatcher(MatchType.EXACT_NAME, className);
    }

    public static ClassMatcher byNamePattern(String regex) {
        return new ClassMatcher(MatchType.NAME_PATTERN, regex);
    }

    public static ClassMatcher byAnnotation(String annotationClassName) {
        return new ClassMatcher(MatchType.ANNOTATION, annotationClassName);
    }

    public static ClassMatcher bySuperClass(String superClassName) {
        return new ClassMatcher(MatchType.SUPER_CLASS, superClassName);
    }

    public static ClassMatcher byInterface(String interfaceName) {
        return new ClassMatcher(MatchType.INTERFACE, interfaceName);
    }

    public MatchType getMatchType() {
        return matchType;
    }

    public String getPattern() {
        return pattern;
    }

    public boolean matches(String className) {
        switch (matchType) {
            case EXACT_NAME:
                return pattern.equals(className);
            case NAME_PATTERN:
                return compiledRegex != null && compiledRegex.matcher(className).matches();
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
