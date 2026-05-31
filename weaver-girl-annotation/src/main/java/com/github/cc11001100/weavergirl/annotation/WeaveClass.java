package com.github.cc11001100.weavergirl.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an interceptor for a target class.
 * The annotated class should contain methods annotated with @Before, @After, or @Around.
 *
 * <p>Example:</p>
 * <pre>
 *   {@code @WeaveClass(target = "com.example.UserService")}
 *   public class UserServiceInterceptor {
 *       {@code @Before("createUser")}
 *       public void beforeCreate(MethodInvocation invocation) { ... }
 *   }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WeaveClass {

    /**
     * Fully qualified name of the target class to intercept.
     */
    String target();

    /**
     * Optional name pattern (regex) as alternative to exact target name.
     */
    String targetPattern() default "";
}
