package com.github.cc11001100.weavergirl.core.introduction;

import java.lang.reflect.Method;
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
      Object delegate = IntroductionStore.resolveWithName(target);
      if (delegate == null) {
        log.warn("No delegate found for target instance in introduction");
        return null;
      }

      // Find the corresponding method on the delegate
      Method delegateMethod = findDelegateMethod(delegate, method);
      if (delegateMethod == null) {
        log.warn(
            "No matching method found on delegate for {}",
            method.getName());
        return null;
      }

      // Invoke the delegate method
      if (IntroductionDelegate.methodTakesTarget(delegateMethod, method)) {
        // A delegate that only implements the interface cannot see the object it is answering
        // for, which limits it to constants. Such a delegate asks for the target by declaring it
        // as the first parameter instead.
        Object[] withTarget = prepend(target, args);
        return delegateMethod.invoke(delegate, withTarget);
      }
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

  /**
   * The delegate's method for an interface method: either the plain interface method, or the same
   * method with the target object as an extra first parameter.
   *
   * <p>The second shape is what lets a delegate read the object it is answering for. It is tried
   * second so an existing delegate that happens to be assignable from the interface keeps working
   * unchanged.
   *
   * @param delegate the delegate instance
   * @param interfaceMethod the interface method being called
   * @return the method to invoke, or {@code null} when the delegate answers neither shape
   */
  public static Method findDelegateMethod(Object delegate, Method interfaceMethod) {
    Class<?> delegateClass = delegate.getClass();
    String methodName = interfaceMethod.getName();
    Class<?>[] paramTypes = interfaceMethod.getParameterTypes();
    try {
      return delegateClass.getMethod(methodName, paramTypes);
    } catch (NoSuchMethodException plainMissing) {
      // Fall through to the target-taking shape.
    }
    // The target-taking shape is matched by structure, not by exact types: a delegate may declare
    // the concrete class or Object for the first parameter, and neither is the interface's own type.
    for (Method method : delegateClass.getMethods()) {
      if (!method.getName().equals(methodName)) {
        continue;
      }
      Class<?>[] params = method.getParameterTypes();
      if (java.util.Arrays.equals(params, paramTypes)) {
        return method;
      }
      if (params.length == paramTypes.length + 1
          && params[0].isAssignableFrom(interfaceMethod.getDeclaringClass())) {
        boolean sameTail = true;
        for (int i = 0; i < paramTypes.length; i++) {
          if (!params[i + 1].equals(paramTypes[i])) {
            sameTail = false;
            break;
          }
        }
        if (sameTail) {
          return method;
        }
      }
    }
    return null;
  }

  /**
   * Whether {@code delegateMethod} answers an interface method by taking the target as an extra
   * first parameter.
   *
   * @param delegateMethod the method found on the delegate
   * @param interfaceMethod the interface method being called
   * @return true when the target has to be prepended to the arguments
   */
  public static boolean methodTakesTarget(Method delegateMethod, Method interfaceMethod) {
    return delegateMethod.getParameterCount() == interfaceMethod.getParameterCount() + 1;
  }

  private static Object[] prepend(Object target, Object[] args) {
    int extra = args == null ? 0 : args.length;
    Object[] all = new Object[extra + 1];
    all[0] = target;
    if (extra > 0) {
      System.arraycopy(args, 0, all, 1, extra);
    }
    return all;
  }
}
