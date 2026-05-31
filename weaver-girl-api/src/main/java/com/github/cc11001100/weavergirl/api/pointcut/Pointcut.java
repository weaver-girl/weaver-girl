package com.github.cc11001100.weavergirl.api.pointcut;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;

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

    @Override
    public String toString() {
        return "Pointcut{class=" + classMatcher + ", method=" + methodMatcher + "}";
    }
}
