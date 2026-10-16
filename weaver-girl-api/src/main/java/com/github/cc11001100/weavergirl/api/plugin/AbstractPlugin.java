package com.github.cc11001100.weavergirl.api.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.CatchInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.FieldMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.CatchPointcut;
import com.github.cc11001100.weavergirl.api.pointcut.FieldPointcut;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;

/**
 * Base plugin class providing a convenient fluent builder API for registering interceptors.
 *
 * <p>Extend this class instead of implementing {@link WeaverPlugin} directly to use the fluent
 * builder pattern, which reduces boilerplate and makes interceptor definitions more readable. Each
 * builder starts with an {@code intercept*()} method that selects the target class, then chains
 * method selection, callback registration, and finally {@link InterceptorDefinitionBuilder#build()}
 * to produce an {@link InterceptorDefinition}.
 *
 * <h3>Fluent builder entry points</h3>
 *
 * <ul>
 *   <li>{@link #intercept(String)} &mdash; match class by exact name
 *   <li>{@link #interceptClassPattern(String)} &mdash; match class by regex pattern
 *   <li>{@link #interceptAnnotated(String)} &mdash; match class by annotation
 *   <li>{@link #interceptSubclassOf(String)} &mdash; match class by superclass
 *   <li>{@link #interceptImplementing(String)} &mdash; match class by interface
 *   <li>{@link #interceptField(String, String)} &mdash; intercept reads and writes of one field
 *   <li>{@link #interceptCatch(String, String)} &mdash; intercept catch blocks for one exception type
 * </ul>
 *
 * <p>Field and catch interception are separate chains from method interception. A method definition is
 * woven with the standard advice; a field definition is woven with field advice and dispatched to
 * {@link FieldInterceptor}; a catch definition is woven with catch advice and dispatched to {@link
 * CatchInterceptor}. The builders do not mix the two into one definition, because that would weave the
 * wrong advice and the interceptor would never be called.
 *
 * <h3>Usage example</h3>
 *
 * <pre>
 * public class MyPlugin extends AbstractPlugin {
 *     &#64;Override
 *     public String name() { return "my-plugin"; }
 *
 *     &#64;Override
 *     public void registerInterceptors(InterceptorRegistry registry) {
 *         // Intercept a specific method on a specific class
 *         registry.register(
 *             intercept("com.example.Service")
 *                 .method("process")
 *                 .before(inv -&gt; System.out.println("before: " + inv.getMethodName()))
 *                 .build()
 *         );
 *
 *         // Intercept all methods on classes matching a pattern
 *         registry.register(
 *             interceptClassPattern("com\\.example\\..*Service")
 *                 .methodPattern("process.*")
 *                 .around(
 *                     inv -&gt; System.out.println("before: " + inv.getMethodName()),
 *                     inv -&gt; System.out.println("after: " + inv.getMethodName())
 *                 )
 *                 .build()
 *         );
 *
 *         // Intercept methods on annotated classes with priority
 *         registry.register(
 *             interceptAnnotated("com.example.Traced")
 *                 .anyMethod()
 *                 .after(inv -&gt; recordMetric(inv))
 *                 .priority(10)
 *                 .build()
 *         );
 *     }
 * }</pre>
 *
 * @see WeaverPlugin
 * @see InterceptorDefinition
 * @see InterceptorDefinitionBuilder
 * @since 1.0.0
 */
public abstract class AbstractPlugin implements WeaverPlugin {

  /**
   * The class named with {@link #instrumentedLibrary(String)}, or null when this plugin does not target a
   * third-party library. The loader reads it through {@link WeaverPlugin#instrumentedLibrary()} and skips
   * interceptor registration when the class cannot be seen.
   */
  private String instrumentedLibraryClass;

  /**
   * Name the class that has to be visible for this plugin to do anything, for example {@code
   * "io.grpc.Server"}. Called from {@link #init(PluginContext)}, which runs before the loader checks. A
   * plugin that never calls this is activated exactly as before.
   *
   * @param className a fully-qualified class name from the instrumented library
   */
  protected final void instrumentedLibrary(String className) {
    this.instrumentedLibraryClass = className;
  }

  @Override
  public String instrumentedLibrary() {
    return instrumentedLibraryClass;
  }

  /**
   * Start a fluent interceptor definition for a class matched by exact fully-qualified name.
   *
   * @param className the fully-qualified class name (e.g., {@code "com.example.Service"})
   * @return a new builder with the class matcher set to exact name matching
   */
  protected InterceptorDefinitionBuilder intercept(String className) {
    return new InterceptorDefinitionBuilder(this, ClassMatcher.byName(className));
  }

  /**
   * Start a fluent interceptor definition for classes matched by regex name pattern.
   *
   * @param classPattern a Java regex pattern (e.g., {@code "com\\.example\\..*Service"})
   * @return a new builder with the class matcher set to name pattern matching
   */
  protected InterceptorDefinitionBuilder interceptClassPattern(String classPattern) {
    return new InterceptorDefinitionBuilder(this, ClassMatcher.byNamePattern(classPattern));
  }

  /**
   * Start a fluent interceptor definition for classes annotated with the given annotation.
   *
   * @param annotationClassName the fully-qualified annotation class name (e.g., {@code
   *     "com.example.Traced"})
   * @return a new builder with the class matcher set to annotation matching
   */
  protected InterceptorDefinitionBuilder interceptAnnotated(String annotationClassName) {
    return new InterceptorDefinitionBuilder(this, ClassMatcher.byAnnotation(annotationClassName));
  }

  /**
   * Start a fluent interceptor definition for classes that subclass the given class.
   *
   * @param superClassName the fully-qualified superclass name (e.g., {@code
   *     "com.example.BaseService"})
   * @return a new builder with the class matcher set to superclass matching
   */
  protected InterceptorDefinitionBuilder interceptSubclassOf(String superClassName) {
    return new InterceptorDefinitionBuilder(this, ClassMatcher.bySuperClass(superClassName));
  }

  /**
   * Start a fluent interceptor definition for classes that implement the given interface.
   *
   * @param interfaceName the fully-qualified interface name (e.g., {@code "java.io.Serializable"})
   * @return a new builder with the class matcher set to interface matching
   */
  protected InterceptorDefinitionBuilder interceptImplementing(String interfaceName) {
    return new InterceptorDefinitionBuilder(this, ClassMatcher.byInterface(interfaceName));
  }

  /**
   * Start a field-interception definition for one field of a class matched by exact name.
   *
   * <p>The resulting definition is woven with field advice rather than method advice, and its
   * interceptor implements {@link FieldInterceptor}. Both reads and writes of the field are matched;
   * use {@link FieldInterceptorDefinitionBuilder#onGet(FieldGetCallback)} and {@link
   * FieldInterceptorDefinitionBuilder#onSet(FieldSetCallback)} to choose which to observe. The field
   * is selected inside the advice through {@link FieldInterceptor#fieldPointcuts()}, so the
   * definition's method matcher only decides which classes are woven.
   *
   * @param className the fully-qualified class name declaring the field
   * @param fieldName the exact field name
   * @return a new field builder
   * @since 2.1.0
   */
  protected FieldInterceptorDefinitionBuilder interceptField(String className, String fieldName) {
    return new FieldInterceptorDefinitionBuilder(
        this, ClassMatcher.byName(className), FieldMatcher.byName(fieldName));
  }

  /**
   * Start a field-interception definition for every field matching a matcher, on classes matched by
   * exact name.
   *
   * @param className the fully-qualified class name declaring the fields
   * @param fieldMatcher selects the fields, for example {@link FieldMatcher#byType(String)} or {@link
   *     FieldMatcher#byNamePattern(String)}
   * @return a new field builder
   * @since 2.1.0
   */
  protected FieldInterceptorDefinitionBuilder interceptField(
      String className, FieldMatcher fieldMatcher) {
    return new FieldInterceptorDefinitionBuilder(this, ClassMatcher.byName(className), fieldMatcher);
  }

  /**
   * Start a catch-block interception definition for one exception type inside a class matched by
   * exact name.
   *
   * <p>The resulting definition is woven with catch advice, and its interceptor implements {@link
   * CatchInterceptor}. A catch block matches when the caught exception is of {@code exceptionTypeName}
   * or a subclass of it.
   *
   * @param className the fully-qualified class name whose catch blocks are intercepted
   * @param exceptionTypeName the fully-qualified exception type name, for example {@code
   *     "java.io.IOException"}
   * @return a new catch builder
   * @since 2.1.0
   */
  protected CatchInterceptorDefinitionBuilder interceptCatch(
      String className, String exceptionTypeName) {
    return new CatchInterceptorDefinitionBuilder(
        this, ClassMatcher.byName(className), exceptionTypeName);
  }

  /**
   * Fluent builder for constructing {@link InterceptorDefinition} instances.
   *
   * <p>Typical usage: start with an {@code intercept*()} method on {@link AbstractPlugin}, chain
   * method selection (e.g., {@link #method(String)}), add callback(s) (e.g., {@link
   * #before(BeforeCallback)}), and call {@link #build()} to produce the definition.
   *
   * <p>If no method matcher is specified, {@link MethodMatcher#any()} is used. If no interceptor
   * callback is set, a no-op interceptor is used.
   *
   * @since 1.0.0
   */
  protected static class InterceptorDefinitionBuilder {
    private final AbstractPlugin plugin;
    private final ClassMatcher classMatcher;
    private MethodMatcher methodMatcher;
    private Interceptor interceptor;
    private int priority = 0;
    private String name;

    InterceptorDefinitionBuilder(AbstractPlugin plugin, ClassMatcher classMatcher) {
      this.plugin = plugin;
      this.classMatcher = classMatcher;
      this.methodMatcher = MethodMatcher.any();
    }

    /**
     * Match methods by exact name.
     *
     * @param methodName the method name to match
     * @return this builder for chaining
     */
    public InterceptorDefinitionBuilder method(String methodName) {
      this.methodMatcher = MethodMatcher.byName(methodName);
      return this;
    }

    /**
     * Match methods by regex name pattern.
     *
     * @param methodPattern a Java regex pattern against method names
     * @return this builder for chaining
     */
    public InterceptorDefinitionBuilder methodPattern(String methodPattern) {
      this.methodMatcher = MethodMatcher.byNamePattern(methodPattern);
      return this;
    }

    /**
     * Match methods annotated with the given annotation.
     *
     * @param annotationClassName the fully-qualified annotation class name
     * @return this builder for chaining
     */
    public InterceptorDefinitionBuilder methodAnnotated(String annotationClassName) {
      this.methodMatcher = MethodMatcher.byAnnotation(annotationClassName);
      return this;
    }

    /**
     * Match all methods (default if no method matcher is specified).
     *
     * @return this builder for chaining
     */
    public InterceptorDefinitionBuilder anyMethod() {
      this.methodMatcher = MethodMatcher.any();
      return this;
    }

    /**
     * Name this definition explicitly.
     *
     * <p>The registry keeps one definition per name and replaces the previous one on a clash, and the
     * default name is derived only from the plugin, class and method. Two interceptors on the same
     * method — one that decides whether to proceed, one that records the outcome — would therefore
     * collapse into whichever was registered last. Give each its own name when more than one targets
     * the same method.
     *
     * @param name the definition name, unique within the registry
     * @return this builder for chaining
     */
    public InterceptorDefinitionBuilder name(String name) {
      this.name = name;
      return this;
    }

    /**
     * Set the priority for this interceptor definition.
     *
     * <p>Lower values = higher priority (executed first). The default is 0.
     *
     * @param priority the priority value
     * @return this builder for chaining
     */
    public InterceptorDefinitionBuilder priority(int priority) {
      this.priority = priority;
      return this;
    }

    /**
     * Register a callback to be invoked before the target method executes.
     *
     * @param callback the before callback
     * @return this builder for chaining
     */
    public InterceptorDefinitionBuilder before(final BeforeCallback callback) {
      this.interceptor =
          new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
              callback.before(invocation);
            }
          };
      return this;
    }

    /**
     * Register a callback to be invoked after the target method executes successfully.
     *
     * @param callback the after callback
     * @return this builder for chaining
     */
    public InterceptorDefinitionBuilder after(final AfterCallback callback) {
      this.interceptor =
          new Interceptor() {
            @Override
            public void after(MethodInvocation invocation) {
              callback.after(invocation);
            }
          };
      return this;
    }

    /**
     * Register a callback to be invoked when the target method throws an exception.
     *
     * @param callback the exception callback
     * @return this builder for chaining
     */
    public InterceptorDefinitionBuilder onException(final ExceptionCallback callback) {
      this.interceptor =
          new Interceptor() {
            @Override
            public void onException(MethodInvocation invocation) {
              callback.onException(invocation);
            }
          };
      return this;
    }

    /**
     * Register both a before and after callback, creating an around-advice interceptor.
     *
     * <p>The after callback is also invoked on exception, allowing cleanup logic to run regardless
     * of whether the method succeeded or threw.
     *
     * @param beforeCallback the before callback
     * @param afterCallback the after callback (also called on exception)
     * @return this builder for chaining
     */
    public InterceptorDefinitionBuilder around(
        final BeforeCallback beforeCallback, final AfterCallback afterCallback) {
      this.interceptor =
          new Interceptor() {
            @Override
            public void before(MethodInvocation invocation) {
              beforeCallback.before(invocation);
            }

            @Override
            public void after(MethodInvocation invocation) {
              afterCallback.after(invocation);
            }

            @Override
            public void onException(MethodInvocation invocation) {
              afterCallback.after(invocation);
            }
          };
      return this;
    }

    /**
     * Register an around-advice callback that wraps the target method execution. The interceptor
     * must call {@link MethodInvocation#proceed()} to continue the chain; otherwise the method is
     * skipped.
     *
     * @param callback the around callback
     * @return this builder for chaining
     * @since 1.6.0
     */
    /**
     * Register an around callback together with the callbacks that see how the call ended.
     *
     * <p>Each of {@link #around(AroundCallback)}, {@link #onException(ExceptionCallback)} and {@link
     * #after(AfterCallback)} replaces the interceptor set so far, so chaining them keeps only the
     * last one. A circuit breaker needs all three at once: the around callback decides whether the
     * call proceeds, and only the exit callbacks can see whether it failed. Registering them as
     * three definitions is not the same thing either — every definition weaves the advice into the
     * method again, so the method would run once per definition.
     *
     * <p>The exit callbacks are invoked by the advice on the way out, not by this around callback.
     * {@code onFailure} runs when the call threw, {@code onSuccess} otherwise, including when the
     * around callback answered the call itself and never called {@link MethodInvocation#proceed()}.
     *
     * @param aroundCallback decides whether the call proceeds and may set a return value
     * @param onSuccess invoked on the way out when the call did not throw
     * @param onFailure invoked on the way out when the call threw
     * @return this builder for chaining
     */
    public InterceptorDefinitionBuilder around(
        final AroundCallback aroundCallback,
        final AfterCallback onSuccess,
        final ExceptionCallback onFailure) {
      this.interceptor =
          new Interceptor() {
            @Override
            public void around(MethodInvocation invocation) {
              aroundCallback.around(invocation);
            }

            @Override
            public boolean hasAround() {
              return true;
            }

            @Override
            public void after(MethodInvocation invocation) {
              onSuccess.after(invocation);
            }

            @Override
            public void onException(MethodInvocation invocation) {
              onFailure.onException(invocation);
            }
          };
      return this;
    }

    public InterceptorDefinitionBuilder around(final AroundCallback callback) {
      this.interceptor =
          new Interceptor() {
            @Override
            public void around(MethodInvocation invocation) {
              callback.around(invocation);
            }

            @Override
            public boolean hasAround() {
              return true;
            }
          };
      return this;
    }

    /**
     * Build the interceptor definition from this builder's configuration.
     *
     * <p>The definition name is auto-generated from the plugin name, class matcher pattern, and
     * method matcher pattern. If no interceptor was set, a no-op interceptor is used.
     *
     * @return a new InterceptorDefinition
     */
    public InterceptorDefinition build() {
      Pointcut pointcut = new Pointcut(classMatcher, methodMatcher);
      if (interceptor == null) {
        interceptor = new Interceptor() {};
      }
      String definitionName =
          name != null
              ? name
              : plugin.name() + "-" + classMatcher.getPattern() + "-" + methodMatcher.getPattern();
      return new InterceptorDefinition(definitionName, pointcut, interceptor, priority);
    }
  }

  /**
   * Fluent builder for a field-interception definition.
   *
   * <p>Produced by {@link #interceptField(String, String)}. The definition it builds carries {@link
   * InterceptorDefinition.AdviceMode#FIELD}, so the transformer weaves field advice, and its
   * interceptor implements {@link FieldInterceptor} with a {@link FieldPointcut} for the selected
   * field. Without that combination the field advice never runs and the callbacks are never called.
   *
   * @since 2.1.0
   */
  protected static class FieldInterceptorDefinitionBuilder {
    private final AbstractPlugin plugin;
    private final ClassMatcher classMatcher;
    private final FieldMatcher fieldMatcher;
    private FieldGetCallback onGet;
    private FieldSetCallback onSet;
    private int priority = 0;

    FieldInterceptorDefinitionBuilder(
        AbstractPlugin plugin, ClassMatcher classMatcher, FieldMatcher fieldMatcher) {
      this.plugin = plugin;
      this.classMatcher = classMatcher;
      this.fieldMatcher = fieldMatcher;
    }

    /**
     * Called when the matched field is read.
     *
     * <p>The callback receives the value read from the field and may replace it through {@link
     * FieldInvocation#setReturnValue(Object)}.
     *
     * @param callback the read callback
     * @return this builder for chaining
     */
    public FieldInterceptorDefinitionBuilder onGet(FieldGetCallback callback) {
      this.onGet = callback;
      return this;
    }

    /**
     * Called when the matched field is written.
     *
     * <p>The callback receives the value about to be stored and may replace it through {@link
     * FieldInvocation#setWriteValue(Object)} or reject the write through {@link
     * FieldInvocation#skipWrite()}.
     *
     * @param callback the write callback
     * @return this builder for chaining
     */
    public FieldInterceptorDefinitionBuilder onSet(FieldSetCallback callback) {
      this.onSet = callback;
      return this;
    }

    /**
     * Sets the execution priority. Lower values run first. The default is 0.
     *
     * @param priority the priority value
     * @return this builder for chaining
     */
    public FieldInterceptorDefinitionBuilder priority(int priority) {
      this.priority = priority;
      return this;
    }

    /**
     * Builds the field-interception definition.
     *
     * <p>The definition name is derived from the plugin name, the class matcher and the field matcher,
     * so two field definitions in one plugin stay distinct.
     *
     * @return a definition woven with field advice
     */
    public InterceptorDefinition build() {
      FieldPointcut fieldPointcut = FieldPointcut.inClass(classMatcher, fieldMatcher);
      final FieldPointcut[] pointcuts = new FieldPointcut[] {fieldPointcut};
      final FieldGetCallback getCallback = onGet;
      final FieldSetCallback setCallback = onSet;
      final int fieldPriority = priority;
      Interceptor interceptor =
          new FieldInterceptorAdapter(pointcuts, fieldPriority, getCallback, setCallback);
      return new InterceptorDefinition(
          plugin.name() + "-field-" + classMatcher.getPattern() + "-" + fieldMatcher.getPattern(),
          new Pointcut(classMatcher, MethodMatcher.any()),
          interceptor,
          priority,
          InterceptorDefinition.AdviceMode.FIELD);
    }
  }

  /**
   * Bridges the builder callbacks to {@link FieldInterceptor}. The advice selects interceptors by
   * {@code instanceof FieldInterceptor} and then asks {@link #fieldPointcuts()} which fields to fire
   * on, so both have to be present for the callbacks to run.
   */
  private static final class FieldInterceptorAdapter implements Interceptor, FieldInterceptor {
    private final FieldPointcut[] pointcuts;
    private final int priority;
    private final FieldGetCallback onGet;
    private final FieldSetCallback onSet;

    FieldInterceptorAdapter(
        FieldPointcut[] pointcuts,
        int priority,
        FieldGetCallback onGet,
        FieldSetCallback onSet) {
      this.pointcuts = pointcuts;
      this.priority = priority;
      this.onGet = onGet;
      this.onSet = onSet;
    }

    @Override
    public FieldPointcut[] fieldPointcuts() {
      return pointcuts;
    }

    @Override
    public int getPriority() {
      return priority;
    }

    @Override
    public void onFieldGet(FieldInvocation invocation, Object originalValue) {
      if (onGet != null) {
        onGet.onGet(invocation, originalValue);
      }
    }

    @Override
    public void onFieldSet(FieldInvocation invocation, Object target, Object originalValue) {
      if (onSet != null) {
        onSet.onSet(invocation, target, originalValue);
      }
    }
  }

  /**
   * Fluent builder for a catch-block interception definition.
   *
   * <p>Produced by {@link #interceptCatch(String, String)}. The definition it builds carries {@link
   * InterceptorDefinition.AdviceMode#CATCH} and an interceptor that implements {@link
   * CatchInterceptor}, which is the combination the transformer and the catch advice both require.
   *
   * @since 2.1.0
   */
  protected static class CatchInterceptorDefinitionBuilder {
    private final AbstractPlugin plugin;
    private final ClassMatcher classMatcher;
    private final String exceptionTypeName;
    private CatchCallback onCatch;
    private int priority = 0;

    CatchInterceptorDefinitionBuilder(
        AbstractPlugin plugin, ClassMatcher classMatcher, String exceptionTypeName) {
      this.plugin = plugin;
      this.classMatcher = classMatcher;
      this.exceptionTypeName = exceptionTypeName;
    }

    /**
     * Called when a matching catch block is entered.
     *
     * <p>The callback may suppress the catch block, replace the caught exception, or override the
     * value the catch block returns, through the {@link CatchInvocation} it receives.
     *
     * @param callback the catch callback
     * @return this builder for chaining
     */
    public CatchInterceptorDefinitionBuilder onCatch(CatchCallback callback) {
      this.onCatch = callback;
      return this;
    }

    /**
     * Sets the execution priority. Lower values run first. The default is 0.
     *
     * @param priority the priority value
     * @return this builder for chaining
     */
    public CatchInterceptorDefinitionBuilder priority(int priority) {
      this.priority = priority;
      return this;
    }

    /**
     * Builds the catch-block interception definition.
     *
     * @return a definition woven with catch advice
     */
    public InterceptorDefinition build() {
      Pointcut anyMethod = new Pointcut(classMatcher, MethodMatcher.any());
      final CatchPointcut[] pointcuts = {new CatchPointcut(anyMethod, exceptionTypeName)};
      final CatchCallback callback = onCatch;
      final int catchPriority = priority;
      Interceptor interceptor = new CatchInterceptorAdapter(pointcuts, catchPriority, callback);
      return new InterceptorDefinition(
          plugin.name() + "-catch-" + classMatcher.getPattern() + "-" + exceptionTypeName,
          anyMethod,
          interceptor,
          priority,
          InterceptorDefinition.AdviceMode.CATCH,
          false,
          null,
          exceptionTypeName);
    }
  }

  /**
   * Bridges the builder callback to {@link CatchInterceptor}. The catch advice only invokes an
   * interceptor it can cast to that interface, and only for catch blocks its {@link #catchPointcuts()}
   * accept.
   */
  private static final class CatchInterceptorAdapter implements Interceptor, CatchInterceptor {
    private final CatchPointcut[] pointcuts;
    private final int priority;
    private final CatchCallback onCatch;

    CatchInterceptorAdapter(CatchPointcut[] pointcuts, int priority, CatchCallback onCatch) {
      this.pointcuts = pointcuts;
      this.priority = priority;
      this.onCatch = onCatch;
    }

    @Override
    public CatchPointcut[] catchPointcuts() {
      return pointcuts;
    }

    @Override
    public int getPriority() {
      return priority;
    }

    @Override
    public void onCatch(CatchInvocation invocation) {
      if (onCatch != null) {
        onCatch.onCatch(invocation);
      }
    }
  }

  /**
   * Functional interface for around-advice callbacks.
   *
   * @since 1.6.0
   */
  @FunctionalInterface
  protected interface AroundCallback {
    /**
     * Around advice: called before the target method executes. Implementations must call {@link
     * MethodInvocation#proceed()} to continue the interception chain, or omit it to short-circuit
     * the call.
     *
     * @param invocation context object for the intercepted method call
     */
    void around(MethodInvocation invocation);
  }

  /**
   * Functional interface for before-advice callbacks.
   *
   * @since 1.0.0
   */
  @FunctionalInterface
  protected interface BeforeCallback {
    /**
     * Called before the target method executes.
     *
     * @param invocation context object for the intercepted method call
     */
    void before(MethodInvocation invocation);
  }

  /**
   * Functional interface for after-advice callbacks.
   *
   * @since 1.0.0
   */
  @FunctionalInterface
  protected interface AfterCallback {
    /**
     * Called after the target method executes.
     *
     * @param invocation context object for the intercepted method call
     */
    void after(MethodInvocation invocation);
  }

  /**
   * Functional interface for exception-advice callbacks.
   *
   * @since 1.0.0
   */
  @FunctionalInterface
  protected interface ExceptionCallback {
    /**
     * Called when the target method throws an exception.
     *
     * @param invocation context object for the intercepted method call
     */
    void onException(MethodInvocation invocation);
  }

  /**
   * Callback for a field read. The {@code value} is what the field held; replace it with {@link
   * FieldInvocation#setReturnValue(Object)} to change what the reader observes.
   *
   * @since 2.1.0
   */
  @FunctionalInterface
  protected interface FieldGetCallback {
    /**
     * Called when the matched field is read.
     *
     * @param invocation context for the field access
     * @param value the value read from the field, which may be null
     */
    void onGet(FieldInvocation invocation, Object value);
  }

  /**
   * Callback for a field write. The {@code value} is what is about to be stored; replace it with
   * {@link FieldInvocation#setWriteValue(Object)} or reject the write with {@link
   * FieldInvocation#skipWrite()}.
   *
   * @since 2.1.0
   */
  @FunctionalInterface
  protected interface FieldSetCallback {
    /**
     * Called when the matched field is written.
     *
     * @param invocation context for the field access
     * @param target the object whose field is written, or null for a static field
     * @param value the value about to be stored, which may be null
     */
    void onSet(FieldInvocation invocation, Object target, Object value);
  }

  /**
   * Callback for a catch block. Suppress the block, replace the caught exception, or override the
   * value the block returns through the {@link CatchInvocation}.
   *
   * @since 2.1.0
   */
  @FunctionalInterface
  protected interface CatchCallback {
    /**
     * Called when a matching catch block is entered.
     *
     * @param invocation context describing the catch block and the caught exception
     */
    void onCatch(CatchInvocation invocation);
  }
}
