package com.github.cc11001100.weavergirl.api.interceptor;

/**
 * Context object passed to interceptors at runtime.
 * Encapsulates all information about the intercepted method call.
 *
 * <p>Interceptors can:</p>
 * <ul>
 *   <li>Read target class, method name, arguments</li>
 *   <li>Set return value (to override original return)</li>
 *   <li>Call {@link #skipMethod()} to prevent original method execution</li>
 *   <li>Access thrown exception in onException callback</li>
 * </ul>
 */
public class MethodInvocation {

    private final Class<?> targetClass;
    private final String methodName;
    private final Object target;
    private final Object[] arguments;
    private Object returnValue;
    private Throwable throwable;
    private boolean isSkipped;

    public MethodInvocation(Class<?> targetClass, String methodName,
                            Object target, Object[] arguments) {
        this.targetClass = targetClass;
        this.methodName = methodName;
        this.target = target;
        this.arguments = arguments != null ? arguments : new Object[0];
        this.isSkipped = false;
    }

    public Class<?> getTargetClass() {
        return targetClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public Object getTarget() {
        return target;
    }

    public Object[] getArguments() {
        return arguments;
    }

    public Object getArgument(int index) {
        if (index < 0 || index >= arguments.length) {
            throw new IndexOutOfBoundsException(
                    "Argument index " + index + " out of bounds for " + arguments.length + " arguments");
        }
        return arguments[index];
    }

    public Object getReturnValue() {
        return returnValue;
    }

    public void setReturnValue(Object returnValue) {
        this.returnValue = returnValue;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }

    public boolean hasException() {
        return throwable != null;
    }

    public boolean isSkipped() {
        return isSkipped;
    }

    public void skipMethod() {
        this.isSkipped = true;
    }
}
