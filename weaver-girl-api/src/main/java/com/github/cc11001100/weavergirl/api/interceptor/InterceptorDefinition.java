package com.github.cc11001100.weavergirl.api.interceptor;

import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;

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
    public String toString() {
        return "InterceptorDefinition{name='" + name + "', pointcut=" + pointcut + ", priority=" + priority + "}";
    }
}
