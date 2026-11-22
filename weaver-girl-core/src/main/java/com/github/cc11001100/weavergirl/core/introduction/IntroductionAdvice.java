package com.github.cc11001100.weavergirl.core.introduction;

import com.github.cc11001100.weavergirl.api.introduction.IntroductionDefinition;
import com.github.cc11001100.weavergirl.api.introduction.IntroductionRegistry;
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

  /**
   * The delegate methods an introduction contributes, as the method names to delegate.
   *
   * <p>Read by the transformer when it installs the introduction: it implements the interface on
   * the target and routes each of these methods through {@link IntroductionDelegate#intercept}. A
   * definition whose delegate lacks a method simply omits it, which is what the runtime advice does
   * too.
   *
   * @param definition the introduction to install
   * @return the interface methods the delegate can answer, never null
   */
  public static java.util.Set<String> delegatableMethodNames(IntroductionDefinition definition) {
    java.util.Set<String> names = new java.util.LinkedHashSet<String>();
    for (java.lang.reflect.Method contract : definition.getIntroductionType().getMethods()) {
      if (IntroductionDelegate.findDelegateMethod(definition.getDelegate(), contract) != null) {
        names.add(contract.getName());
      }
      // Otherwise the delegate does not answer this one; the woven method would throw at call
      // time, so it is left out of the interface rather than made to fail.
    }
    return names;
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
      Object delegate = IntroductionStore.resolveWithName(target);
      if (delegate == null) {
        log.warn(
            "No delegate found for target class {} in introduction {}",
            targetClass.getName(),
            matchingDef.getName());
        return null;
      }

      // Find the corresponding method on the delegate
      Method delegateMethod = IntroductionDelegate.findDelegateMethod(delegate, method);
      if (delegateMethod == null) {
        log.warn(
            "No matching method found on delegate for {}#{}",
            targetClass.getName(),
            method.getName());
        return null;
      }

      // Return a DelegateInvocation that carries the delegate, method, and call arguments
      return new DelegateInvocation(delegate, delegateMethod, method, target, arguments);
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
        if (IntroductionDelegate.methodTakesTarget(delegateMethod, methodOf(invocation))) {
          Object[] all = new Object[(args == null ? 0 : args.length) + 1];
          all[0] = invocation.getTarget();
          if (args != null && args.length > 0) {
            System.arraycopy(args, 0, all, 1, args.length);
          }
          args = all;
        }
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



  /** The interface method this invocation answers, so the target-taking shape can be detected. */
  private static Method methodOf(DelegateInvocation invocation) {
    return invocation.getInterfaceMethod();
  }

  // Simple holder for delegate invocation state
  private static class DelegateInvocation {
    private final Object delegate;
    private final Method delegateMethod;
    private final Method interfaceMethod;
    private final Object[] arguments;
    private final Object target;

    DelegateInvocation(
        Object delegate,
        Method delegateMethod,
        Method interfaceMethod,
        Object target,
        Object[] arguments) {
      this.delegate = delegate;
      this.delegateMethod = delegateMethod;
      this.interfaceMethod = interfaceMethod;
      this.arguments = arguments;
      this.target = target;
    }

    Object getTarget() {
      return target;
    }

    Method getInterfaceMethod() {
      return interfaceMethod;
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
