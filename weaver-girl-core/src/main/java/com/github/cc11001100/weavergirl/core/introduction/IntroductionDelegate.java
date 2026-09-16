package com.github.cc11001100.weavergirl.core.introduction;

import java.lang.reflect.Method;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Method delegation target for introduction (mixin) support.
 *
 * <p>When a class is introduced with an interface, ByteBuddy generates delegating methods
 * that forward to this class. It looks up the per-instance delegate from {@link IntroductionStore}
 * and invokes the corresponding method.
 *
 * @since 2.0.0
 */
public class IntroductionDelegate {

  private static final Logger log = LoggerFactory.getLogger(IntroductionDelegate.class);

  /**
   * Intercept all introduced interface methods and delegate to the per-instance delegate.
   *
   * @param target the instrumented instance
   * @param method the interface method being intercepted
   * @param args the method arguments
   * @return the result of the delegate method, or null if no delegate is found
   */
  @RuntimeType
  public static Object intercept(
      @This Object target,
      @Origin Method method,
      @AllArguments Object[] args) {
    try {
      Object delegate = IntroductionStore.getDelegate(target);
      if (delegate == null) {
        log.warn("No delegate found for target instance in introduction");
        return null;
      }

      // Find the corresponding method on the delegate
      Method delegateMethod = findDelegateMethod(delegate.getClass(), method);
      if (delegateMethod == null) {
        log.warn(
            "No matching method found on delegate for {}",
            method.getName());
        return null;
      }

      // Invoke the delegate method
      if (args == null || args.length == 0) {
        return delegateMethod.invoke(delegate);
      } else {
        return delegateMethod.invoke(delegate, args);
      }
    } catch (java.lang.reflect.InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      } else if (cause instanceof Error) {
        throw (Error) cause;
      } else {
        throw new RuntimeException(cause);
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke delegate method", e);
    }
  }

  private static Method findDelegateMethod(Class<?> delegateClass, Method interfaceMethod) {
    String methodName = interfaceMethod.getName();
    Class<?>[] paramTypes = interfaceMethod.getParameterTypes();

    try {
      return delegateClass.getMethod(methodName, paramTypes);
    } catch (NoSuchMethodException e) {
      // Try to find a method with compatible parameter types
      for (Method method : delegateClass.getMethods()) {
        if (method.getName().equals(methodName)
            && java.util.Arrays.equals(method.getParameterTypes(), paramTypes)) {
          return method;
        }
      }
      return null;
    }
  }
}
