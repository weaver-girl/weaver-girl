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
 * <h3>Weave-time field binding</h3>
 *
 * <p>ByteBuddy's {@code @Advice.Origin} does not support {@link Field} origin types (only {@code
 * Class}, {@code Method}, {@code Executable}, {@code MethodHandle}, {@code MethodType}, {@code
 * MethodHandles.Lookup} and {@link String}), so the woven advice cannot bind "the currently accessed
 * field" directly at a method join point. Instead it binds the enclosing method name via {@code
 * @Advice.Origin("#m")} and derives the field from the accessor-name convention ({@code getX},
 * {@code isX}, {@code setX}, {@code access$X}): the derived field name is resolved reflectively on
 * the instrumented class (walking the superclass chain), then tagged onto the acquired {@link
 * FieldInvocation} attachment so the exit advice reuses the exact same {@code Field}. Non-accessor
 * methods (no resolvable field) are left untouched — the enter advice returns {@code null} and the
 * exit advice becomes a no-op.
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

  /** Invocation attachment carrying the resolved {@link Field} from enter advice to exit advice. */
  private static final String FIELD_ORIGIN_ATTACHMENT = "__field_origin__";

  @Advice.OnMethodEnter
  public static FieldInvocation onWovenEnter(
      @Advice.Origin Class<?> targetClass,
      @Advice.Origin("#m") String methodName,
      @Advice.This(optional = true) Object target,
      @Advice.Argument(
              value = 0,
              readOnly = false,
              optional = true,
              typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC)
          Object writeValue) {
    try {
      if (!InterceptorHolder.isInterceptionEnabled()) {
        return null;
      }
      Field field = resolveFieldForAccessor(targetClass, methodName);
      if (field == null) {
        // Not an accessor of a woven field; nothing to intercept at this join point.
        return null;
      }
      Object originalWriteValue = writeValue;
      FieldInvocation invocation = dispatchEnter(targetClass, field, target, writeValue);
      if (invocation != null) {
        invocation.setAttachment(FIELD_ORIGIN_ATTACHMENT, field);
        // dispatchEnter mutates a copy of the argument, so apply what the interceptor decided to the
        // bound @Advice.Argument(0, readOnly = false) variable here: only a reassignment performed in
        // this advice method body is written back into the woven call.
        if (originalWriteValue != null) {
          if (invocation.isWriteSkipped()) {
            writeValue = originalWriteValue;
          } else if (invocation.isWriteOverridden()) {
            writeValue = invocation.getWriteValue();
          }
        }
      }
      return invocation;
    } catch (Throwable e) {
      return null;
    }
  }

  /**
   * Derives the field accessed by a getter/setter-style method and resolves it reflectively.
   *
   * <p>Strips the {@code get}/{@code is}/{@code set}/{@code access$} prefix, decapitalises the
   * remainder, and walks the class hierarchy looking for that field (falling back to the undecap'd
   * suffix, e.g. for acronym-ish names like {@code getURL}). Returns {@code null} when the method is
   * not a resolvable accessor.
   *
   * <p>Public: invoked from the inlined {@code onWovenEnter} advice inside the woven target class,
   * which lives in the application's package — a package-private method would fail with {@code
   * IllegalAccessError} across the package boundary and be silently swallowed by the advice's
   * catch-all (the woven field hook "just never fires").
   */
  public static Field resolveFieldForAccessor(Class<?> targetClass, String methodName) {
    if (methodName == null || methodName.isEmpty()) {
      return null;
    }
    String suffix = null;
    if (methodName.startsWith("access$") && methodName.length() > "access$".length()) {
      suffix = methodName.substring("access$".length());
    } else if (methodName.startsWith("get") && methodName.length() > 3) {
      suffix = methodName.substring(3);
    } else if (methodName.startsWith("is") && methodName.length() > 2) {
      suffix = methodName.substring(2);
    } else if (methodName.startsWith("set") && methodName.length() > 3) {
      suffix = methodName.substring(3);
    }
    if (suffix == null || suffix.isEmpty()) {
      return null;
    }
    String decap = decapitalize(suffix);
    String[] candidates = decap.equals(suffix) ? new String[] {decap} : new String[] {decap, suffix};
    Class<?> current = targetClass;
    while (current != null) {
      for (String candidate : candidates) {
        try {
          return current.getDeclaredField(candidate);
        } catch (NoSuchFieldException ignored) {
          // keep searching
        }
      }
      current = current.getSuperclass();
    }
    return null;
  }

  private static String decapitalize(String s) {
    char[] chars = s.toCharArray();
    chars[0] = Character.toLowerCase(chars[0]);
    return new String(chars);
  }

  /**
   * Direct-call entry point used by unit tests: dispatch field access with an explicit {@link
   * Field}, bypassing ByteBuddy weaving. {@code writeValue == null} denotes a getfield access,
   * non-null a putfield access.
   *
   * @param targetClass the class declaring the field
   * @param field the accessed field
   * @param target the owning instance, or null for static fields
   * @param writeValue the value being written (null for reads)
   * @return the acquired invocation, or null when interception/sampling is off
   */
  public static FieldInvocation onFieldEnter(
      Class<?> targetClass, Field field, Object target, Object writeValue) {
    return dispatchEnter(targetClass, field, target, writeValue);
  }

  /**
   * Shared enter-phase dispatch: guards, sampling, field-pointcut matching, interceptor
   * invocation, and write skipping. Returns the acquired {@link FieldInvocation} (or {@code null}
   * when interception is off / not sampled).
   *
   * <p>Public and {@code static}: the woven advice ({@code onWovenEnter}) lives in the target
   * application's package — see {@link #resolveFieldForAccessor} for the visibility rationale.
   */
  public static FieldInvocation dispatchEnter(
      Class<?> targetClass, Field field, Object target, Object writeValue) {
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

      for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition def :
          registry.getAllDefinitions()) {
        if (!(def.getInterceptor() instanceof FieldInterceptor)) {
          continue;
        }
        FieldInterceptor fieldInterceptor = (FieldInterceptor) def.getInterceptor();
        for (com.github.cc11001100.weavergirl.api.pointcut.FieldPointcut fp :
            fieldInterceptor.fieldPointcuts()) {
          // Pass the real Class, not just its name: matches(Class, ...) can evaluate
          // byInterface/bySuperClass/byAnnotation reflectively, while matches(String, ...)
          // rejects all three (returns false for INTERFACE/SUPER_CLASS, name-equality for
          // ANNOTATION). A target woven via a non-name class matcher would otherwise be
          // "woven but never dispatched". methodName is intentionally null, as the original
          // string-based call also passed null (field advice does not bind the enclosing method).
          if (fp.matches(targetClass, null, fieldName, fieldTypeName)) {
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

  @Advice.OnMethodExit(onThrowable = Throwable.class, backupArguments = false)
  public static void onWovenExit(
      @Advice.Enter FieldInvocation invocation,
      @Advice.Return(
              readOnly = false,
              typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC)
          Object returnValue,
      @Advice.Thrown(
              readOnly = false,
              typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC)
          Throwable throwable) {
    Field field =
        invocation != null ? invocation.getAttachment(FIELD_ORIGIN_ATTACHMENT, Field.class) : null;
    // Read overrides have to be assigned here, in the advice method body, to reach the bound
    // @Advice.Return(readOnly = false) variable; dispatchExit only sees a copy of it.
    if (invocation != null && throwable == null && invocation.isReturnOverridden()) {
      returnValue = invocation.getReturnValue();
    }
    dispatchExit(invocation, null, field, returnValue, throwable);
  }

  /**
   * Direct-call exit entry point used by unit tests: release the invocation and publish the field
   * return snapshot with an explicit {@link Field}.
   *
   * @param invocation the invocation produced by enter (may be null)
   * @param targetClass the class declaring the field (unused; kept for API stability)
   * @param field the accessed field
   * @param target the owning instance (unused; kept for API stability)
   * @param returnValue the method's return value
   * @param throwable the thrown throwable, if any
   */
  public static void onFieldExit(
      FieldInvocation invocation,
      Class<?> targetClass,
      Field field,
      Object target,
      Object returnValue,
      Throwable throwable) {
    dispatchExit(invocation, targetClass, field, returnValue, throwable);
  }

  /**
   * Shared exit-phase dispatch: release the pooled invocation, apply any return override, and
   * version the field return value for downstream consumers. Mirrors {@code dispatchEnter} by being
   * defensive over every step so the field access path never breaks due to bookkeeping.
   *
   * <p>Public and {@code static}: the woven advice ({@code onWovenExit}) lives in the target
   * application's package — see {@link #resolveFieldForAccessor} for the visibility rationale.
   */
  public static void dispatchExit(
      FieldInvocation invocation,
      Class<?> targetClass,
      Field field,
      Object returnValue,
      Throwable throwable) {
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
      if (throwable == null && field != null) {
        String fieldKey = invocation.getTargetClass().getName() + "." + field.getName();
        try {
          long version =
              com.github.cc11001100.weavergirl.api.interceptor.ReturnVersion.next(fieldKey);
          com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot snapshot =
              new com.github.cc11001100.weavergirl.api.interceptor.ReturnSnapshot(
                  returnValue, version, field.getName(), field.getType());
          invocation.setReturnSnapshot(snapshot);
          // The invocation is pooled and cleared right after this method returns, which would
          // otherwise null out returnSnapshot before any consumer could read it. Publish the
          // snapshot to the durable registry so downstream consumers can still observe it.
          com.github.cc11001100.weavergirl.api.interceptor.ReturnVersion.recordSnapshot(
              fieldKey, snapshot);
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