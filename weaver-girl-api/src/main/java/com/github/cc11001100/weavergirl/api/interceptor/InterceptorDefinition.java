package com.github.cc11001100.weavergirl.api.interceptor;

import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;

import java.util.Objects;

/**
 * A complete interceptor definition that binds a {@link Pointcut} to an {@link Interceptor}.
 *
 * <p>This is the fundamental unit registered with the
 * {@link com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry}.
 * It tells the framework <em>where</em> to intercept (via the pointcut) and
 * <em>what</em> to do (via the interceptor).</p>
 *
 * <h3>Name uniqueness</h3>
 * <p>The {@code name} field must be unique across all registered definitions. It is used
 * as the key for {@link com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry#unregister(String)}.
 * Attempting to register a definition with a name that is already registered will replace
 * the existing definition.</p>
 *
 * <h3>Priority</h3>
 * <p>The {@code priority} field determines execution order when multiple interceptors
 * target the same method. Lower values indicate higher priority (executed first).
 * The default priority is 0.</p>
 *
 * <h3>Usage example</h3>
 * <pre>
 * InterceptorDefinition def = new InterceptorDefinition(
 *     "my-timing-interceptor",
 *     new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process")),
 *     myInterceptor,
 *     10
 * );
 * registry.register(def);</pre>
 *
 * @see Interceptor
 * @see Pointcut
 * @see com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry
 * @since 1.0.0
 */
public class InterceptorDefinition {

    private final String name;
    private final Pointcut pointcut;
    private final Interceptor interceptor;
    private final int priority;

    /**
     * Creates a new interceptor definition with default priority (0).
     *
     * @param name        unique name for this definition, used for unregistration
     * @param pointcut    determines which classes and methods are intercepted
     * @param interceptor the interceptor to invoke when the pointcut matches
     */
    public InterceptorDefinition(String name, Pointcut pointcut, Interceptor interceptor) {
        this(name, pointcut, interceptor, 0);
    }

    /**
     * Creates a new interceptor definition with the specified priority.
     *
     * @param name        unique name for this definition, used for unregistration
     * @param pointcut    determines which classes and methods are intercepted
     * @param interceptor the interceptor to invoke when the pointcut matches
     * @param priority    execution priority; lower values = higher priority (executed first)
     */
    public InterceptorDefinition(String name, Pointcut pointcut, Interceptor interceptor, int priority) {
        this.name = name;
        this.pointcut = pointcut;
        this.interceptor = interceptor;
        this.priority = priority;
    }

    /**
     * Returns the unique name of this interceptor definition.
     *
     * <p>The name is used as a key for unregistration via
     * {@link com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry#unregister(String)}.</p>
     *
     * @return the definition name
     */
    public String getName() { return name; }

    /**
     * Returns the pointcut that determines which classes and methods this interceptor applies to.
     *
     * @return the pointcut, never null
     */
    public Pointcut getPointcut() { return pointcut; }

    /**
     * Returns the interceptor that is invoked when the pointcut matches.
     *
     * @return the interceptor, never null
     */
    public Interceptor getInterceptor() { return interceptor; }

    /**
     * Returns the execution priority of this interceptor definition.
     *
     * <p>Lower values indicate higher priority (executed first). The default is 0.</p>
     *
     * @return the priority value
     */
    public int getPriority() { return priority; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InterceptorDefinition)) return false;
        InterceptorDefinition that = (InterceptorDefinition) o;
        return priority == that.priority
                && Objects.equals(name, that.name)
                && Objects.equals(pointcut, that.pointcut);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, pointcut, priority);
    }

    @Override
    public String toString() {
        return "InterceptorDefinition{name='" + name + "', pointcut=" + pointcut + ", priority=" + priority + "}";
    }
}
