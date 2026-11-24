package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.context.MdcInjector;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.status.HookTelemetry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy Advice class that gets inlined into target <strong>methods</strong> (regular and
 * static). For constructor interception, see {@link ConstructorAdvice}.
 *
 * <p>Delegates to InterceptorRegistry for interceptor lookup and invocation.
 *
 * <p><strong>skipOn mechanism:</strong> ByteBuddy's {@code skipOn = MethodInvocation.class} means:
 * if onMethodEnter returns a non-null MethodInvocation, the original method body is SKIPPED. If it
 * returns null, the original method executes normally.
 *
 * <p><strong>Return value write-back:</strong> {@code @Advice.Return(readOnly = false)} allows the
 * exit advice to write a modified return value back to the caller. When an interceptor calls {@code
 * invocation.setReturnValue(newValue)}, we assign it to the returnValue parameter, and ByteBuddy
 * stores it into the return-value local variable slot.
 *
 * <p><strong>Automatic MDC injection:</strong> When SLF4J is available, this advice automatically
 * injects trace/span/tenant context from ThreadContext into MDC before method execution and
 * restores the previous MDC state after method completion. This ensures application log statements
 * are automatically correlated with the current trace context without manual MDC management.
 */
public class InterceptAdvice {

  public static final ThreadLocal<MdcInjector.MdcSnapshot> MDC_SNAPSHOT =
      new ThreadLocal<>();

  /**
   * Marker returned by {@link #onMethodEnter} when the exit advice has nothing to do. It is a
   * plain {@code Object}, not a {@link MethodInvocation}, so it does not trigger ByteBuddy's
   * {@code skipOn} — the target method still runs.
   */
  public static final Object NOTHING = new Object();

  /**
   * Carries the per-call state from enter to exit.
   *
   * <p>Holding the invocation across the target method means the enter call's before-phase
   * attachments are still there on exit, so nothing has to be drained and restored between the two
   * advices. It also carries the MDC snapshot, whether a cflow frame was pushed, and the dispatch
   * view enter resolved (with the registry generation it was resolved at), so exit does not repeat
   * the lookup and does not need a ThreadLocal or a global cflow check. Public because the inlined
   * advice code lives in the application's package.
   */
  public static final class EnterState {
    public final MethodInvocation invocation;
    public final MdcInjector.MdcSnapshot mdc;
    public final boolean cflowEntered;

    /**
     * The dispatch view enter used. Exit reuses it instead of calling {@code dispatch()} again,
     * which removes a hash lookup and the registry-generation check from every successful
     * invocation. It is only valid while the registry generation is unchanged, so exit also
     * carries the generation it was taken at.
     */
    public final InterceptorHolder.DispatchView dispatch;
    public final long generation;

    /** Public because the inlined advice constructs it in the application's package. */
    public EnterState(
        MethodInvocation invocation,
        MdcInjector.MdcSnapshot mdc,
        boolean cflowEntered,
        InterceptorHolder.DispatchView dispatch,
        long generation) {
      this.invocation = invocation;
      this.mdc = mdc;
      this.cflowEntered = cflowEntered;
      this.dispatch = dispatch;
      this.generation = generation;
    }
  }

  @Advice.OnMethodEnter(skipOn = MethodInvocation.class)
  public static Object onMethodEnter(
      @Advice.Origin Class<?> targetClass,
      @Advice.Origin Method method,
      @Advice.This(optional = true) Object target,
      @Advice.AllArguments Object[] arguments) {
    try {
      // Global kill-switch: one volatile read; when disabled, incur zero dispatch cost.
      // NOTHING rather than null: the exit advice then bails out immediately instead of taking
      // the defensive path that allocates an invocation only to discard it unused.
      if (!InterceptorHolder.isInterceptionEnabled()) {
        return NOTHING;
      }
      InterceptorRegistry registry = InterceptorHolder.getRegistry();
      if (registry == null) {
        return NOTHING;
      }

      String methodName = method.getName();
      String className = targetClass.getName();
      InterceptorHolder.DispatchView dispatch = InterceptorHolder.dispatch(className, methodName);
      InterceptorDefinition[] defs = dispatch.definitions;
      if (defs.length == 0 || !dispatch.hasStandardDefs) {
        // Nothing can apply to this method in the current registry generation (or every match is
        // an ARGUMENT_REWRITE hook handled by AsyncArgumentAdvice). Do not count, sample, touch
        // MDC, walk the stack or allocate an invocation: tell the exit advice there is
        // nothing to do either.
        return NOTHING;
      }

      InterceptorHolder.incrementInterceptorInvocationCount();

      // Sampling check: skip interception if not sampled this invocation.
      if (!SamplingController.getInstance().shouldSample()) {
        return NOTHING;
      }

      // Automatic MDC injection: capture previous MDC state and inject trace/span/tenant
      // context from ThreadContext so application logs are automatically correlated.
      MdcInjector.MdcSnapshot mdc = null;
      if (MdcInjector.isMdcAvailable()) {
        mdc = MdcInjector.inject();
      }

      // Per-call allocation instead of a pooled instance. Measured on this machine: a pooled
      // acquire+release pair costs ~38 ns — two ThreadLocal operations plus two full-field resets
      // — while a fresh MethodInvocation costs ~7 ns. The pool saves an allocation into a TLAB
      // and pays more than it saves, so the advice allocates.
      MethodInvocation invocation =
          new MethodInvocation(targetClass, methodName, method, target, arguments);

      // Call-site capture is opt-in per definition: a stack walk plus a Class.forName costs about
      // 1 µs per call, so only a hook that actually reads getCallerClass() asks for it. Delegated to
      // InterceptorHolder because ByteBuddy rejects an advice method that references
      // getStackTrace.
      if (dispatch.tracksCallSite) {
        InterceptorHolder.captureCaller(invocation);
      }

      // cflow tracking: push current method onto the cflow stack before evaluating
      // runtime conditions, so nested cflow expressions can see this method in their
      // caller chain. Pop on exit. Skipped entirely — no string, no ThreadLocal work — when no
      // cflow() expression has ever been built, which is the overwhelmingly common case.
      boolean cflowEntered = PointcutExpression.isCflowTrackingRequired();
      if (cflowEntered) {
        PointcutExpression.enterCflow(className, methodName);
      }
      // Allocating the around list is only worth it when some definition can use it.
      List<InterceptorDefinition> aroundDefs = dispatch.anyAround ? new ArrayList<>() : null;
      for (int i = 0; i < defs.length; i++) {
        InterceptorDefinition def = defs[i];
        if (def.getAdviceMode() == InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE) {
          continue; // before-only mode, dispatched by AsyncArgumentAdvice on entry
        }
        if (def.getPointcut().matchRuntime(className, methodName, null, arguments, null, null)) {
          if (!InterceptorHolder.shouldInvoke(def.getName())) {
            continue; // circuit breaker is open
          }
          if (aroundDefs != null && def.getInterceptor().hasAround()) {
            aroundDefs.add(def);
          } else {
            // Invoke @Before / non-around callbacks before any @Around advice.
            long hookStart = HookTelemetry.startNanos();
            try {
              def.getInterceptor().before(invocation);
              HookTelemetry.record(def.getName(), true, hookStart);
            } catch (Throwable e) {
              InterceptorHolder.logInterceptorError(def.getName(), "before", e);
              HookTelemetry.record(def.getName(), false, hookStart);
            }
          }
        }

        if (dispatch.anyInstanceBound
            && def.getPointcut().isInstanceBound()
            && !invocation.hasPerInstance()) {
          Object perInstance = PerInstanceStore.getOrCreate(target);
          invocation.setPerInstance(perInstance);
        }
      }

      // Execute around-advice callbacks in priority order. Each around interceptor
      // may call proceed() to continue, or skip the method entirely. If any
      // around interceptor skips, the original method body does not execute.
      //
      // proceedCalled is a chain-wide flag: once one interceptor calls proceed(), it
      // stays true for the rest of the chain, so it cannot tell whether THIS interceptor
      // proceeded. The depth delta does — a callback that advanced the depth proceeded,
      // a callback that left it unchanged did not and short-circuits the call.
      boolean proceedAllowed = true;
      for (int a = 0; aroundDefs != null && a < aroundDefs.size(); a++) {
        InterceptorDefinition def = aroundDefs.get(a);
        long hookStart = HookTelemetry.startNanos();
        try {
          // Mark invocation as proceedable before invoking around advice
          invocation.setProceedable(true);
          int depthBefore = invocation.getProceedDepth();
          def.getInterceptor().around(invocation);
          if (invocation.getProceedDepth() == depthBefore) {
            proceedAllowed = false;
            invocation.setSkipMethod(true);
          }
          HookTelemetry.record(def.getName(), true, hookStart);
        } catch (Throwable e) {
          InterceptorHolder.logInterceptorError(def.getName(), "around", e);
          HookTelemetry.record(def.getName(), false, hookStart);
          proceedAllowed = false;
          invocation.setSkipMethod(true);
        }
      }

      // If any around interceptor did not call proceed(), prevent the method from executing.
      // This distinguishes "interceptor skipped" from "interceptor ran but forgot to call proceed"
      // and avoids the previous isProceedable() check which conflated depth exhaustion with skipping.
      if (invocation.isSkipped() || !proceedAllowed) {
        // Returning the invocation triggers skipOn: the original method body does not execute,
        // and onMethodExit receives this same invocation via @Advice.Enter and releases it. The
        // MDC snapshot has to travel through the ThreadLocal here because the skip signal itself
        // must be the invocation.
        if (mdc != null) {
          MDC_SNAPSHOT.set(mdc);
        }
        return invocation;
      }

      // Not skipped: keep the invocation alive across the target method. Exit reads it from this
      // carrier, so before-phase attachments stay on the invocation instead of being drained and
      // restored. The dispatch view travels too, so exit does not repeat the registry lookup.
      return new EnterState(
          invocation, mdc, cflowEntered, dispatch, InterceptorHolder.currentGeneration());
    } catch (Throwable e) {
      // Never let any error (including OOM, StackOverflow) escape the advice
      return null;
    }
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  @SuppressWarnings("unchecked")
  public static void onMethodExit(
      @Advice.Enter Object enterValue,
      @Advice.Origin Class<?> targetClass,
      @Advice.Origin Method method,
      @Advice.This(optional = true) Object target,
      @Advice.AllArguments Object[] arguments,
      @Advice.Thrown(readOnly = false, typing = Assigner.Typing.DYNAMIC) Throwable throwable,
      @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returnValue) {
    try {
      // Enter found nothing to do (no applicable definitions, or not sampled): there is nothing
      // for exit to report either.
      if (enterValue == NOTHING) {
        return;
      }

      // Global kill-switch: when disabled, skip all after/onException callbacks.
      // Drop whatever state enter left behind.
      if (!InterceptorHolder.isInterceptionEnabled()) {
        releaseEntered(enterValue);
        return;
      }

      String methodName = method.getName();

      // Resolve the invocation and the per-call state enter left behind:
      // - MethodInvocation: the skip path, which must return the invocation itself to skipOn.
      // - EnterState: the normal path; the same invocation is still alive.
      // - null/Map: enter bailed out defensively; keep the old behaviour of acquiring a context
      //   so after/onException callbacks still see the call.
      MethodInvocation context;
      MdcInjector.MdcSnapshot mdc = null;
      boolean mdcFromThreadLocal = false;
      boolean cflowEntered;
      InterceptorHolder.DispatchView carried = null;
      long carriedGeneration = 0L;
      if (enterValue instanceof MethodInvocation) {
        context = (MethodInvocation) enterValue;
        mdc = MDC_SNAPSHOT.get();
        mdcFromThreadLocal = true;
        cflowEntered = PointcutExpression.isCflowTrackingRequired();
      } else if (enterValue instanceof EnterState) {
        EnterState state = (EnterState) enterValue;
        context = state.invocation;
        mdc = state.mdc;
        cflowEntered = state.cflowEntered;
        carried = state.dispatch;
        carriedGeneration = state.generation;
      } else {
        // Defensive path: enter bailed out before it could hand anything over. The invocation is
        // fresh, so there is nothing to release.
        context = new MethodInvocation(targetClass, methodName, method, target, arguments);
        if (enterValue instanceof Map) {
          context.restoreAttachments((Map<String, Object>) enterValue);
        }
        mdc = MDC_SNAPSHOT.get();
        mdcFromThreadLocal = true;
        cflowEntered = PointcutExpression.isCflowTrackingRequired();
      }

      // Store the original return value / throwable into the invocation context.
      // In the skip path the invocation is reused, and before/around advice may have already
      // set a return value via setReturnValue(); initReturnValue() would clobber it with the
      // (null) default return slot that ByteBuddy left behind for the skipped body, so only
      // initialize when nothing has been overridden yet.
      if (throwable != null) {
        InterceptorHolder.incrementInterceptorErrorCount();
        context.setThrowable(throwable);
      } else if (!context.isReturnOverridden()) {
        context.initReturnValue(returnValue);
      }

      String className = targetClass.getName();

      InterceptorRegistry registry = InterceptorHolder.getRegistry();
      if (registry == null) {
        return;
      }

      // Reuse the view enter resolved whenever the registry has not changed since. A registration
      // during the call bumps the generation, so a changed hook set falls back to a fresh lookup
      // rather than running against a stale view.
      InterceptorHolder.DispatchView dispatch;
      if (carried != null && carriedGeneration == InterceptorHolder.currentGeneration()) {
        dispatch = carried;
      } else {
        dispatch = InterceptorHolder.dispatch(className, methodName);
      }
      InterceptorDefinition[] defs = dispatch.definitions;
      for (int d = 0; d < defs.length; d++) {
        InterceptorDefinition def = defs[d];
        if (def.getAdviceMode() == InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE) {
          continue; // before-only mode, dispatched by AsyncArgumentAdvice on entry
        }
        if (def.getPointcut().matchRuntime(
            className, methodName, null, arguments, returnValue, throwable)) {
          if (!InterceptorHolder.shouldInvoke(def.getName())) {
            continue; // circuit breaker is open
          }
          long hookStart = HookTelemetry.startNanos();
          try {
            Interceptor interceptor = def.getInterceptor();
            if (throwable != null) {
              interceptor.onException(context);
              // A resilience wrapper (e.g. @RetryOnException) may have re-invoked the
              // target body and replaced the throwable with the last attempt's failure.
              // The advice's throwable parameter is bound at exit entry, so re-read the
              // context so the write-back below propagates the replacement. Suppressed
              // exceptions are handled by the suppression branch below and left intact.
              Throwable current = context.getThrowable();
              if (current != null && !context.isExceptionSuppressed()) {
                throwable = current;
              }
            } else {
              interceptor.after(context);
              // @Timeout injects a TimeoutException into the context when the method ran
              // longer than its deadline; surface it to the caller instead of letting the
              // stale (successful) return slip through. Normal interceptors never set a
              // throwable in after(), so this stays a no-op for everyone else.
              Throwable current = context.getThrowable();
              if (current != null && !context.isExceptionSuppressed()) {
                throwable = current;
              }
            }
            try {
              interceptor.afterFinally(context);
            } catch (Throwable e) {
              InterceptorHolder.logInterceptorError(def.getName(), "afterFinally", e);
              HookTelemetry.record(def.getName(), false, hookStart);
            }
            HookTelemetry.record(def.getName(), true, hookStart);
          } catch (Throwable e) {
            // Catch Throwable to prevent OOM/StackOverflow from plugins
            // crashing the target application
            InterceptorHolder.logInterceptorError(
                def.getName(), throwable != null ? "onException" : "after", e);
            HookTelemetry.record(def.getName(), false, hookStart);
          }
        }
      }

      // Handle exception suppression: if an interceptor called suppressException()
      // and there was a throwable, clear it so it doesn't propagate.
      if (context.isExceptionSuppressed() && throwable != null) {
        throwable = null; // Clear the throwable so it doesn't propagate
        if (context.isReturnOverridden()) {
          returnValue = context.getReturnValue();
        }
      }

      // Write back: if an interceptor explicitly set a return value, replace
      // the original/default return value with the override.
      if (context.isReturnOverridden()) {
        returnValue = context.getReturnValue();
      }

      // Version the return value for downstream consumers (tracing, caching).
      if (throwable == null) {
        try {
          // The counter is resolved once per method by the dispatch view, so the hot path pays an
          // atomic increment instead of a concurrent-map lookup.
          long version = dispatch.versionCounter.incrementAndGet();
          // The snapshot object is the expensive half (~40 ns: one global-id bump plus a clock
          // read) and nothing in the engine reads it back — only a deployment that polls
          // latestSnapshot() does. Recording is therefore switchable; the version counter, which
          // a consumer can pair with its own observation, is always kept.
          if (com.github.cc11001100.weavergirl.api.interceptor.ReturnVersion
              .snapshotRecordingEnabled()) {
            com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot snapshot =
                new com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot(
                    returnValue, version, methodName, method.getReturnType());
            context.setReturnSnapshot(snapshot);
            // Publish to the durable registry as well: a consumer reading it later cannot rely on
            // the per-call invocation.
            com.github.cc11001100.weavergirl.api.interceptor.ReturnVersion.recordSnapshot(
                dispatch.methodKey, snapshot);
          }
        } catch (Throwable versionError) {
          // Never let bookkeeping break the return path
        }
      }

      // Restore MDC to the state before this intercepted method executed. The snapshot normally
      // travels with the call; only the skip path has to use the ThreadLocal.
      if (mdc != null) {
        MdcInjector.restore(mdc);
        if (mdcFromThreadLocal) {
          MDC_SNAPSHOT.remove();
        }
      }

      // cflow tracking: pop the frame enter pushed. EnterState records whether it pushed one; the
      // skip and defensive paths fall back to the global flag, and exitCflow only pops a matching
      // top frame, so the common no-cflow case does no ThreadLocal work at all.
      if (cflowEntered) {
        PointcutExpression.exitCflow(className, methodName);
      }
      // The invocation was allocated for this call only; the JVM collects it. Nothing to return
      // to a pool (see the note in onMethodEnter on why pooling was removed).
    } catch (Throwable e) {
      // Never let any error (including OOM, StackOverflow) crash the target application.
      try {
        releaseEntered(enterValue);
      } catch (Throwable poolError) {
        System.err.println(
            "[weaver-girl] Failed to release MethodInvocation to pool: " + poolError.getMessage());
      }
    }
  }

  /**
   * Drops any per-call state an enter value still holds so a recycled invocation cannot retain
   * the target. The method advice allocates one invocation per call rather than pooling, so there
   * is no pool to return it to; this only clears the references. Public because the inlined
   * advice runs in the application's package.
   *
   * @param enterValue the value {@code onMethodEnter} returned
   */
  public static void releaseEntered(Object enterValue) {
    if (enterValue instanceof MethodInvocation) {
      ((MethodInvocation) enterValue).clear();
    } else if (enterValue instanceof EnterState) {
      ((EnterState) enterValue).invocation.clear();
    }
  }
}
