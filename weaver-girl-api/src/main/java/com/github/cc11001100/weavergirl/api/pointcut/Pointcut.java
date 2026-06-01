package com.github.cc11001100.weavergirl.api.pointcut;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;

import java.util.Objects;

/**
 * Pointcut combines a ClassMatcher and MethodMatcher to define
 * which classes and methods should be intercepted.
 */
public class Pointcut {

    private final ClassMatcher classMatcher;
    private final MethodMatcher methodMatcher;

    public Pointcut(ClassMatcher classMatcher, MethodMatcher methodMatcher) {
        this.classMatcher = classMatcher;
        this.methodMatcher = methodMatcher;
    }

    public ClassMatcher getClassMatcher() {
        return classMatcher;
    }

    public MethodMatcher getMethodMatcher() {
        return methodMatcher;
    }

    /**
     * Check whether the given class and method names match this pointcut.
     */
    public boolean matches(String className, String methodName) {
        return classMatcher.matches(className) && methodMatcher.matches(methodName);
    }

    /**
     * Create a pointcut that matches if BOTH this and the other pointcut match.
     */
    public Pointcut and(Pointcut other) {
        return new Pointcut(
            this.classMatcher,
            this.methodMatcher
        ) {
            @Override
            public boolean matches(String className, String methodName) {
                return Pointcut.this.matches(className, methodName) && other.matches(className, methodName);
            }
        };
    }

    /**
     * Create a pointcut that matches if EITHER this or the other pointcut match.
     */
    public Pointcut or(Pointcut other) {
        return new Pointcut(
            this.classMatcher,
            this.methodMatcher
        ) {
            @Override
            public boolean matches(String className, String methodName) {
                return Pointcut.this.matches(className, methodName) || other.matches(className, methodName);
            }
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Pointcut)) return false;
        Pointcut that = (Pointcut) o;
        return Objects.equals(classMatcher, that.classMatcher) && Objects.equals(methodMatcher, that.methodMatcher);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classMatcher, methodMatcher);
    }

    @Override
    public String toString() {
        return "Pointcut{class=" + classMatcher + ", method=" + methodMatcher + "}";
    }
}
