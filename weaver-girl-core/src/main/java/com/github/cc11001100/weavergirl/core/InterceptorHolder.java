package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.circuit.InterceptorCircuitBreaker;
import com.github.cc11001100.weavergirl.core.management.AgentMonitor;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global holder for the InterceptorRegistry instance. Needed because ByteBuddy Advice classes are
 * static and cannot access instance fields — they need a global reference.
 *
 * <p>Also provides static delegate methods for AgentStatus counters, so that InterceptAdvice (which
 * is inlined by ByteBuddy and cannot reference classes not on the bootstrap classloader) can
 * increment status counters indirectly.
 */
public class InterceptorHolder {

  private static final Logger LOG = LoggerFactory.getLogger(InterceptorHolder.class);

  private static volatile InterceptorRegistry registry;
  private static final InterceptorCircuitBreaker circuitBreaker = new InterceptorCircuitBreaker();

  public static void logInterceptorError(String interceptorName, String phase, Throwable e) {
    LOG.warn("Interceptor '{}' failed in {}: {}", interceptorName, phase, e.getMessage());
  }

  public static void setRegistry(InterceptorRegistry registry) {
    InterceptorHolder.registry = registry;
  }

  public static InterceptorRegistry getRegistry() {
    return registry;
  }

  // --- Global interception switch delegates ---

  /**
   * @return true if global interception is enabled. Consulted by {@code InterceptAdvice} on every
   *     enter/exit as a single volatile read.
   */
  public static boolean isInterceptionEnabled() {
    return GlobalInterceptionSwitch.isEnabled();
  }

  /** Process-wide kill-switch toggle. */
  public static void setInterceptionEnabled(boolean enabledFlag, String source) {
    GlobalInterceptionSwitch.setEnabled(enabledFlag, source);
  }

  // --- AgentStatus delegates ---

  public static void incrementInterceptorInvocationCount() {
    AgentStatus.getInstance().incrementInterceptorInvocationCount();
    AgentMonitor.getInstance().incrementInterceptCount();
  }

  public static void incrementInterceptorErrorCount() {
    AgentStatus.getInstance().incrementInterceptorErrorCount();
  }

  public static void recordInterceptTime(long nanos) {
    AgentMonitor.getInstance().addInterceptTime(nanos);
  }

  // --- Circuit breaker delegates ---

  public static boolean shouldInvoke(String interceptorName) {
    return circuitBreaker.shouldInvoke(interceptorName);
  }

  public static void recordInterceptorSuccess(String interceptorName) {
    circuitBreaker.recordSuccess(interceptorName);
  }

  public static void recordInterceptorFailure(String interceptorName) {
    circuitBreaker.recordFailure(interceptorName);
  }

  /**
   * Record an interceptor outcome and its duration, driving both failure-count and slow-call
   * (auto-degradation) circuit breaking in one call. Used by the inlined {@code @Advice} so a hook
   * that is consistently slow — but never throws — is still tripped.
   */
  public static void recordOutcome(String interceptorName, boolean success, long durationNanos) {
    circuitBreaker.recordOutcome(interceptorName, success, durationNanos);
  }

  /**
   * Read-only snapshot of every interceptor's circuit-breaker state, for the agent's /stats
   * exposition. Returns an empty list until any hook has been observed.
   */
  public static java.util.List<InterceptorCircuitBreaker.BreakerSnapshot> getBreakerSnapshots() {
    return circuitBreaker.snapshot();
  }

  // --- CallSite tracking delegate ---

  /**
   * Capture the caller information for the intercepted method invocation. Called by the inlined
   * advice at method/constructor entry time.
   *
   * <p>This method lives in InterceptorHolder (not in the advice class itself) because ByteBuddy
   * validates the entire advice class when inlining — any method referencing {@code
   * Throwable.getStackTrace()} or {@code Thread.getStackTrace()} in the advice class causes the
   * advice to fail silently. By delegating through InterceptorHolder, the inlined code only
   * contains a static method call, and the actual stack-walking logic stays on the agent
   * classloader.
   *
   * @param invocation the MethodInvocation to populate with caller info
   */
  public static void captureCaller(MethodInvocation invocation) {
    StackTraceElement[] stack = new Throwable().getStackTrace();
    // stack[0] = getStackTrace
    // stack[1] = captureCaller (InterceptorHolder)
    // stack[2] = onMethodEnter (inlined into target method by ByteBuddy)
    // stack[3+] = the actual caller frames
    //
    // We scan from index 3 onward for the first frame that doesn't
    // belong to the target class AND doesn't belong to the agent
    // infrastructure (InterceptorHolder, InterceptAdvice, etc.).
    String targetClassName =
        invocation.getTargetClass() != null ? invocation.getTargetClass().getName() : null;
    int callerIndex = -1;
    for (int i = 3; i < stack.length; i++) {
      String frameClassName = stack[i].getClassName();
      // Skip frames that belong to the intercepted target class
      // (the inlined advice may appear as multiple frames)
      if (frameClassName.equals(targetClassName)) {
        continue;
      }
      // Skip agent infrastructure frames
      if (frameClassName.startsWith("com.github.cc11001100.weavergirl.core.")
          || frameClassName.startsWith("com.github.cc11001100.weavergirl.api.")) {
        continue;
      }
      // Skip JDK reflection / method-handle frames that sit between
      // the real caller and the intercepted method
      if (frameClassName.startsWith("jdk.internal.reflect.")
          || frameClassName.startsWith("sun.reflect.")
          || frameClassName.startsWith("java.lang.reflect.")) {
        continue;
      }
      // Skip test / framework intermediaries (JUnit, TestNG, etc.)
      if (frameClassName.startsWith("org.junit.")
          || frameClassName.startsWith("org.testng.")
          || frameClassName.startsWith("org.junit.platform.commons.")) {
        continue;
      }
      // Skip JDK utility frames that may appear in test runners
      if (frameClassName.startsWith("java.util.") || frameClassName.startsWith("java.lang.")) {
        continue;
      }
      // First non-target, non-agent, non-JDK, non-test frame is the real caller
      callerIndex = i;
      break;
    }
    if (callerIndex >= 0) {
      StackTraceElement callerFrame = stack[callerIndex];
      String callerClassName = callerFrame.getClassName();
      try {
        invocation.setCaller(
            Class.forName(callerClassName, false, Thread.currentThread().getContextClassLoader()),
            callerFrame.getMethodName(),
            callerFrame.getLineNumber());
      } catch (ClassNotFoundException e) {
        // Caller class not visible to our classloader — store what we can
        invocation.setCaller(null, callerFrame.getMethodName(), callerFrame.getLineNumber());
      }
    }
  }
}
