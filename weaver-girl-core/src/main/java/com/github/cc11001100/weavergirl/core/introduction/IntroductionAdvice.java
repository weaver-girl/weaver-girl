package com.github.cc11001100.weavergirl.core.introduction;

import com.github.cc11001100.weavergirl.api.introduction.IntroductionDefinition;
import com.github.cc11001100.weavergirl.api.introduction.IntroductionRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ByteBuddy Advice class that gets inlined into target classes to provide introduction (mixin)
 * support.
 *
 * <p>For each introduction definition, this advice generates delegating methods for all methods
 * declared by the introduction interface. The generated methods retrieve the delegate from {@link
 * IntroductionStore} and forward the call.
 *
 * @since 2.0.0
 */
public class IntroductionAdvice {

  private static final Logger log = LoggerFactory.getLogger(IntroductionAdvice.class);

  // Cache of interface method -> delegate method for fast lookup
  private static final ConcurrentHashMap<Method, Method> METHOD_CACHE = new ConcurrentHashMap<>();

  private static volatile IntroductionRegistry INTRODUCTION_REGISTRY;

  public static void setIntroductionRegistry(IntroductionRegistry registry) {
    INTRODUCTION_REGISTRY = registry;
  }

  private static IntroductionRegistry getIntroductionRegistry() {
    return INTRODUCTION_REGISTRY;
  }

  @Advice.OnMethodEnter
  public static Object onMethodEnter(
      @Advice.Origin Class<?> targetClass,
      @Advice.Origin Method method,
      @Advice.This(optional = true) Object target,
      @Advice.AllArguments Object[] arguments) {
    try {
      IntroductionRegistry registry = getIntroductionRegistry();
      if (registry == null) {
        return null;
      }

      // Find the introduction definition for this target class
      java.util.List<IntroductionDefinition> matchingDefs =
          registry.getDefinitionsForClass(targetClass.getName());
      if (matchingDefs.isEmpty()) {
        return null;
      }

      // For now, use the first matching definition
      IntroductionDefinition matchingDef = matchingDefs.get(0);

      // Get the delegate for this target instance
      Object delegate = IntroductionStore.getDelegate(target);
      if (delegate == null) {
        log.warn(
            "No delegate found for target class {} in introduction {}",
            targetClass.getName(),
            matchingDef.getName());
        return null;
      }

      // Find the corresponding method on the delegate
      Method delegateMethod = findDelegateMethod(matchingDef, method);
      if (delegateMethod == null) {
        log.warn(
            "No matching method found on delegate for {}#{}",
            targetClass.getName(),
            method.getName());
        return null;
      }

      // Return a DelegateInvocation that carries the delegate, method, and call arguments
      return new DelegateInvocation(delegate, delegateMethod, arguments);
    } catch (Throwable e) {
      log.warn("Introduction advice failed for {}#{}: {}", targetClass.getName(), method.getName(), e.getMessage());
      return null;
    }
  }

  @Advice.OnMethodExit
  public static void onMethodExit(
      @Advice.Enter Object delegateInvocation,
      @Advice.Return(readOnly = false, typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Object returnValue,
      @Advice.Thrown(readOnly = false, typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Throwable throwable) {
    if (delegateInvocation instanceof DelegateInvocation) {
      DelegateInvocation invocation = (DelegateInvocation) delegateInvocation;
      try {
        Method delegateMethod = invocation.getDelegateMethod();
        Object delegate = invocation.getDelegate();
        Object[] args = invocation.getArguments();
        Object result;
        if (args == null || args.length == 0) {
          result = delegateMethod.invoke(delegate);
        } else {
          result = delegateMethod.invoke(delegate, args);
        }
        if (throwable == null && result != null && delegateMethod.getReturnType() != void.class) {
          returnValue = result;
        }
      } catch (java.lang.reflect.InvocationTargetException e) {
        Throwable targetException = e.getCause();
        if (targetException != null && !(targetException instanceof RuntimeException)) {
          throwable = targetException instanceof Exception ? targetException : new RuntimeException(targetException);
        }
      } catch (Throwable e) {
        log.warn("Delegate invocation failed: {}", e.getMessage());
      }
    }
  }

  private static Method findDelegateMethod(IntroductionDefinition def, Method method) {
    // Find the method on the delegate that matches the interface method
    Class<?> delegateClass = def.getDelegate().getClass();
    String methodName = method.getName();
    Class<?>[] paramTypes = method.getParameterTypes();

    try {
      return delegateClass.getMethod(methodName, paramTypes);
    } catch (NoSuchMethodException e) {
      // Try to find a method with compatible parameter types
      for (Method m : delegateClass.getMethods()) {
        if (m.getName().equals(methodName)
            && java.util.Arrays.equals(m.getParameterTypes(), paramTypes)) {
          return m;
        }
      }
      return null;
    }
  }

  // Simple holder for delegate invocation state
  private static class DelegateInvocation {
    private final Object delegate;
    private final Method delegateMethod;
    private final Object[] arguments;

    DelegateInvocation(Object delegate, Method delegateMethod, Object... arguments) {
      this.delegate = delegate;
      this.delegateMethod = delegateMethod;
      this.arguments = arguments;
    }

    Object getDelegate() {
      return delegate;
    }

    Method getDelegateMethod() {
      return delegateMethod;
    }

    Object[] getArguments() {
      return arguments;
    }
  }
}
