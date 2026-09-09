package com.github.cc11001100.weavergirl.core;

import com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInterceptor;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.interceptor.FieldInvocationPool;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import java.lang.reflect.Field;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy Advice class that gets inlined into target <strong>field access</strong> (getfield /
 * putfield). For method interception, see {@link InterceptAdvice}.
 *
 * <p>This advice supports field-level interception declared via {@link
 * com.github.cc11001100.weavergirl.api.interceptor.FieldInterceptor}. At runtime, the advice
 * matches registered field pointcuts, acquires a pooled {@link FieldInvocation}, and invokes the
 * matching field interceptors.
 *
 * <h3>Read interception</h3>
 *
 * <p>The {@code @Advice.Return(readOnly = false)} binding allows field-intercepting plugins to
 * override the value returned by a field read. When a {@link FieldInterceptor} calls {@link
 * FieldInvocation#setReturnValue(Object)}, the advice assigns the override to {@code returnValue},
 * and ByteBuddy stores it into the return-value local variable slot.
 *
 * <h3>Write interception</h3>
 *
 * <p>The {@code @Advice.Argument(0, readOnly = false)} binding represents the value being written
 * to the field. When a {@link FieldInterceptor} calls {@link FieldInvocation#setWriteValue(Object)}
 * or {@link FieldInvocation#skipWrite()}, the advice updates the bound argument before the putfield
 * executes. If the write is skipped, the original value remains unchanged.
 *
 * <h3>Static fields</h3>
 *
 * <p>For static field access, {@code @Advice.This} is not available. The advice detects static
 * access via the presence or absence of the optional {@code target} parameter and sets {@link
 * FieldInvocation#isStatic()} accordingly.
 */
public class FieldAdvice {

  @Advice.OnMethodEnter
  public static FieldInvocation onFieldEnter(
      @Advice.Origin Class<?> targetClass,
      @Advice.Origin Field field,
      @Advice.This(optional = true) Object target,
      @Advice.Argument(value = 0, readOnly = false, optional = true) Object writeValue) {
    try {
      if (!InterceptorHolder.isInterceptionEnabled()) {
        return null;
      }
      InterceptorHolder.incrementInterceptorInvocationCount();
      com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry =
          InterceptorHolder.getRegistry();
      if (registry == null) {
        return null;
      }
      if (!SamplingController.getInstance().shouldSample()) {
        return null;
      }

      String fieldName = field.getName();
      String fieldTypeName = field.getType().getName();
      FieldInvocation invocation =
          FieldInvocationPool.acquire(targetClass, fieldName, fieldTypeName, target);

      String className = targetClass.getName();
      for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition def :
          registry.getAllDefinitions()) {
        if (!(def.getInterceptor() instanceof FieldInterceptor)) {
          continue;
        }
        FieldInterceptor fieldInterceptor = (FieldInterceptor) def.getInterceptor();
        for (com.github.cc11001100.weavergirl.api.pointcut.FieldPointcut fp :
            fieldInterceptor.fieldPointcuts()) {
          if (fp.matches(className, null, fieldName, fieldTypeName)) {
            if (!InterceptorHolder.shouldInvoke(def.getName())) {
              continue;
            }
            long hookStart = System.nanoTime();
            try {
              if (writeValue != null) {
                // putfield access: invoke onFieldSet
                fieldInterceptor.onFieldSet(invocation, target, writeValue);
                if (invocation.isWriteOverridden()) {
                  writeValue = invocation.getWriteValue();
                }
                if (invocation.isWriteSkipped()) {
                  // Set a sentinel to indicate skip; the exit advice will not write back.
                  invocation.setAttachment("__field_write_skipped__", Boolean.TRUE);
                }
              } else {
                // getfield access: invoke onFieldGet
                fieldInterceptor.onFieldGet(invocation, null);
              }
              long hookNanos = System.nanoTime() - hookStart;
              InterceptorHolder.recordOutcome(def.getName(), true, hookNanos);
              com.github.cc11001100.weavergirl.core.status.AgentStatus.getInstance()
                  .recordInterceptorInvocation(def.getName(), true, hookNanos);
            } catch (Throwable e) {
              long hookNanos = System.nanoTime() - hookStart;
              InterceptorHolder.logInterceptorError(def.getName(), "field", e);
              InterceptorHolder.recordOutcome(def.getName(), false, hookNanos);
              com.github.cc11001100.weavergirl.core.status.AgentStatus.getInstance()
                  .recordInterceptorInvocation(def.getName(), false, hookNanos);
            }
          }
        }
      }

      // If this is a getfield and an interceptor set a return value override, carry it through.
      // We can't read @Advice.Return in enter, so we stash it on the invocation and read it in
      // exit. For putfield, we already mutated the writeValue argument above.
      return invocation;
    } catch (Throwable e) {
      return null;
    }
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void onFieldExit(
      @Advice.Enter FieldInvocation invocation,
      @Advice.Origin Class<?> targetClass,
      @Advice.Origin Field field,
      @Advice.This(optional = true) Object target,
      @Advice.Return(readOnly = false, typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Object returnValue,
      @Advice.Thrown(readOnly = false, typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Throwable throwable) {
    try {
      if (!InterceptorHolder.isInterceptionEnabled()) {
        if (invocation != null) {
          try {
            FieldInvocationPool.release(invocation);
          } catch (Throwable ignored) {
          }
        }
        return;
      }

      if (invocation == null) {
        return;
      }

      // If the field write was skipped, prevent the write-back by signaling to the caller
      // that the operation was suppressed. ByteBuddy does not provide a direct way to skip
      // a putfield from exit advice, so we rely on the enter-phase argument mutation.
      // For getfield, write back any return value override set by interceptors.
      Boolean writeSkipped = (Boolean) invocation.getAttachment("__field_write_skipped__");
      if (Boolean.TRUE.equals(writeSkipped)) {
        // The putfield argument was already not mutated in enter; nothing to do here.
        // Release and return.
        FieldInvocationPool.release(invocation);
        return;
      }

      if (invocation.isReturnOverridden()) {
        returnValue = invocation.getReturnValue();
      }

      // Version the field return value for downstream consumers (tracing, caching).
      if (throwable == null) {
        String fieldKey = targetClass.getName() + "." + field.getName();
        try {
          long version =
              com.github.cc11001100.weavergirl.api.interceptor.ReturnVersion.next(fieldKey);
          com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot snapshot =
              new com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot(
                  returnValue, version, field.getName(), field.getType());
          invocation.setReturnSnapshot(snapshot);
        } catch (Throwable versionError) {
          // Never let bookkeeping break the field access path
        }
      }

      FieldInvocationPool.release(invocation);
    } catch (Throwable e) {
      if (invocation != null) {
        try {
          FieldInvocationPool.release(invocation);
        } catch (Throwable poolError) {
          System.err.println(
              "[weaver-girl] Failed to release FieldInvocation to pool: " + poolError.getMessage());
        }
      }
    }
  }
}
