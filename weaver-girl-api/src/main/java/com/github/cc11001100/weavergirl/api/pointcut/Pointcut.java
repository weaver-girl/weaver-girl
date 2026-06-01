package com.github.cc11001100.weavergirl.api.pointcut;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;

import java.util.Objects;

/**
 * Combines a {@link ClassMatcher} and {@link MethodMatcher} to define which classes
 * and methods should be intercepted.
 *
 * <p>A pointcut is the "where" of interception: it selects a set of join points
 * (method executions) by matching on class and method criteria. The "what" to do
 * at those join points is defined by an {@link com.github.cc11001100.weavergirl.api.interceptor.Interceptor},
 * bound together in an {@link com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition}.</p>
 *
 * <h3>Composition</h3>
 * <p>Pointcuts can be composed using the {@link #and(Pointcut)} and {@link #or(Pointcut)}
 * methods to create complex matching logic without resorting to elaborate regex patterns.</p>
 *
 * <h3>Usage example</h3>
 * <pre>
 * Pointcut simple = new Pointcut(
 *     ClassMatcher.byName("com.example.Service"),
 *     MethodMatcher.byName("process")
 * );
 *
 * // Compose pointcuts
 * Pointcut composite = new Pointcut(
 *     ClassMatcher.byNamePattern("com\\.example\\..*"),
 *     MethodMatcher.byNamePattern("process.*")
 * ).or(new Pointcut(
 *     ClassMatcher.byAnnotation("com.example.Traced"),
 *     MethodMatcher.any()
 * ));</pre>
 *
 * @see ClassMatcher
 * @see MethodMatcher
 * @see com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition
 * @since 1.0.0
 */
public class Pointcut {

    private final ClassMatcher classMatcher;
    private final MethodMatcher methodMatcher;

    /**
     * Creates a new pointcut with the given class and method matchers.
     *
     * @param classMatcher  matcher for selecting target classes
     * @param methodMatcher matcher for selecting target methods within matched classes
     */
    public Pointcut(ClassMatcher classMatcher, MethodMatcher methodMatcher) {
        this.classMatcher = classMatcher;
        this.methodMatcher = methodMatcher;
    }

    /**
     * Returns the class matcher component of this pointcut.
     *
     * @return the class matcher, never null
     */
    public ClassMatcher getClassMatcher() {
        return classMatcher;
    }

    /**
     * Returns the method matcher component of this pointcut.
     *
     * @return the method matcher, never null
     */
    public MethodMatcher getMethodMatcher() {
        return methodMatcher;
    }

    /**
     * Checks whether the given class and method names match this pointcut.
     *
     * <p>Both the class matcher and method matcher must match for this method
     * to return {@code true}.</p>
     *
     * @param className  the fully-qualified class name to test
     * @param methodName the method name to test
     * @return true if both the class and method matchers match
     */
    public boolean matches(String className, String methodName) {
        return classMatcher.matches(className) && methodMatcher.matches(methodName);
    }

    /**
     * Creates a pointcut that matches if and only if <em>both</em> this pointcut
     * and the other pointcut match.
     *
     * <p>The returned pointcut overrides {@link #matches(String, String)} to evaluate
     * both pointcuts and return their logical AND.</p>
     *
     * @param other the pointcut to AND with this one
     * @return a new composite pointcut
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
     * Creates a pointcut that matches if <em>either</em> this pointcut or the other
     * pointcut matches.
     *
     * <p>The returned pointcut overrides {@link #matches(String, String)} to evaluate
     * both pointcuts and return their logical OR.</p>
     *
     * @param other the pointcut to OR with this one
     * @return a new composite pointcut
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
