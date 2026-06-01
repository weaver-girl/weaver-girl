package com.github.cc11001100.weavergirl.api.interceptor;

import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;

import java.util.Objects;

/**
 * A complete interceptor definition that binds a Pointcut to an Interceptor.
 * This is the fundamental unit registered with the InterceptorRegistry.
 */
public class InterceptorDefinition {

    private final String name;
    private final Pointcut pointcut;
    private final Interceptor interceptor;
    private final int priority;

    public InterceptorDefinition(String name, Pointcut pointcut, Interceptor interceptor) {
        this(name, pointcut, interceptor, 0);
    }

    public InterceptorDefinition(String name, Pointcut pointcut, Interceptor interceptor, int priority) {
        this.name = name;
        this.pointcut = pointcut;
        this.interceptor = interceptor;
        this.priority = priority;
    }

    public String getName() { return name; }
    public Pointcut getPointcut() { return pointcut; }
    public Interceptor getInterceptor() { return interceptor; }
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
