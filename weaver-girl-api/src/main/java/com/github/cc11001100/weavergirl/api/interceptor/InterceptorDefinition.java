package com.github.cc11001100.weavergirl.api.interceptor;

import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import java.util.Objects;

/**
 * A complete interceptor definition that binds a {@link Pointcut} to an {@link Interceptor}.
 *
 * <p>This is the fundamental unit registered with the {@link
 * com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry}. It tells the framework
 * <em>where</em> to intercept (via the pointcut) and <em>what</em> to do (via the interceptor).
 *
 * <h3>Name uniqueness</h3>
 *
 * <p>The {@code name} field must be unique across all registered definitions. It is used as the key
 * for {@link com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry#unregister(String)}.
 * Attempting to register a definition with a name that is already registered will replace the
 * existing definition.
 *
 * <h3>Priority</h3>
 *
 * <p>The {@code priority} field determines execution order when multiple interceptors target the
 * same method. Lower values indicate higher priority (executed first). The default priority is 0.
 *
 * <h3>Usage example</h3>
 *
 * <pre>
 * InterceptorDefinition def = new InterceptorDefinition(
 *     "my-timing-interceptor",
 *     new Pointcut(ClassMatcher.byName("com.example.Service"), MethodMatcher.byName("process")),
 *     myInterceptor,
 *     10
 * );
 * registry.register(def);</pre>
 *
 * @see Interceptor
 * @see Pointcut
 * @see com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry
 * @since 1.0.0
 */
public class InterceptorDefinition {

  /**
   * Selects which inlined advice class the transformer applies for this definition.
   *
   * <p>{@link #STANDARD} uses the default {@code InterceptAdvice} that dispatches to {@link
   * Interceptor#before}/{@code after}/{@code onException} via a pooled {@link MethodInvocation}.
   * This is the right choice for the overwhelming majority of hooks (timing, tracing, metrics,
   * logging, …).
   *
   * <p>{@link #ARGUMENT_REWRITE} uses a specialized advice that binds the first several method
   * arguments with {@code @Advice.Argument(i, readOnly=false, optional=true)} so an interceptor can
   * <em>replace</em> any argument in-place and have the replacement propagate to the method body.
   * This is required because ByteBuddy's {@code @Advice.AllArguments} array — even with {@code
   * readOnly=false} — does not write element mutations back to the parameter slots. The
   * async-context-propagation plugin uses this mode to wrap the {@code Runnable}/{@code Callable}
   * submitted to an {@code Executor}.
   */
  public enum AdviceMode {
    STANDARD,
    ARGUMENT_REWRITE,
    FIELD,
    CATCH,
    STATIC_INIT
  }

  private final String name;
  private final Pointcut pointcut;
  private final Interceptor interceptor;
  private final int priority;
  private final AdviceMode adviceMode;
  private final boolean hasAround;
  private final com.github.cc11001100.weavergirl.api.matcher.FieldMatcher fieldMatcher;
  private final String catchExceptionTypeName;
  /** The fully-qualified aspect class name, used for {@code @DeclarePrecedence} resolution. */
  private final String aspectClassName;

  /**
   * Creates a new interceptor definition with default priority (0).
   *
   * @param name unique name for this definition, used for unregistration
   * @param pointcut determines which classes and methods are intercepted
   * @param interceptor the interceptor to invoke when the pointcut matches
   */
  public InterceptorDefinition(String name, Pointcut pointcut, Interceptor interceptor) {
    this(name, pointcut, interceptor, 0, AdviceMode.STANDARD, false, null, null);
  }

  /**
   * Creates a new interceptor definition with the specified priority.
   *
   * @param name unique name for this definition, used for unregistration
   * @param pointcut determines which classes and methods are intercepted
   * @param interceptor the interceptor to invoke when the pointcut matches
   * @param priority execution priority; lower values = higher priority (executed first)
   */
  public InterceptorDefinition(
      String name, Pointcut pointcut, Interceptor interceptor, int priority) {
    this(name, pointcut, interceptor, priority, AdviceMode.STANDARD, false, null, null);
  }

  /**
   * Creates a new interceptor definition with the specified priority and advice mode.
   *
   * @param name unique name for this definition, used for unregistration
   * @param pointcut determines which classes and methods are intercepted
   * @param interceptor the interceptor to invoke when the pointcut matches
   * @param priority execution priority; lower values = higher priority (executed first)
   * @param adviceMode which inlined advice class to use; see {@link AdviceMode}
   * @since 1.5.0
   */
  public InterceptorDefinition(
      String name,
      Pointcut pointcut,
      Interceptor interceptor,
      int priority,
      AdviceMode adviceMode) {
    this(name, pointcut, interceptor, priority, adviceMode, false, null, null);
  }

  /**
   * Creates a new interceptor definition with all fields specified.
   *
   * @param name unique name for this definition, used for unregistration
   * @param pointcut determines which classes and methods are intercepted
   * @param interceptor the interceptor to invoke when the pointcut matches
   * @param priority execution priority; lower values = higher priority (executed first)
   * @param adviceMode which inlined advice class to use; see {@link AdviceMode}
   * @param hasAround whether this interceptor implements around advice
   * @since 1.6.0
   */
  public InterceptorDefinition(
      String name,
      Pointcut pointcut,
      Interceptor interceptor,
      int priority,
      AdviceMode adviceMode,
      boolean hasAround) {
    this(name, pointcut, interceptor, priority, adviceMode, hasAround, null, null);
  }

  /**
   * Creates a new interceptor definition with all fields specified, including field/catch matchers.
   *
   * @param name unique name for this definition
   * @param pointcut determines which classes and methods are intercepted
   * @param interceptor the interceptor to invoke when the pointcut matches
   * @param priority execution priority
   * @param adviceMode which inlined advice class to use
   * @param hasAround whether this interceptor implements around advice
   * @param fieldMatcher the field matcher for FIELD advice mode, or null
   * @param catchExceptionTypeName the exception type name for CATCH advice mode, or null
   * @since 1.7.0
   */
  public InterceptorDefinition(
      String name,
      Pointcut pointcut,
      Interceptor interceptor,
      int priority,
      AdviceMode adviceMode,
      boolean hasAround,
      com.github.cc11001100.weavergirl.api.matcher.FieldMatcher fieldMatcher,
      String catchExceptionTypeName) {
    this(
        name,
        pointcut,
        interceptor,
        priority,
        adviceMode,
        hasAround,
        fieldMatcher,
        catchExceptionTypeName,
        null);
  }

  /**
   * Creates a new interceptor definition with all fields specified, including the fully-qualified
   * aspect class name used for {@code @DeclarePrecedence} resolution.
   *
   * @param name unique name for this definition
   * @param pointcut determines which classes and methods are intercepted
   * @param interceptor the interceptor to invoke when the pointcut matches
   * @param priority execution priority
   * @param adviceMode which inlined advice class to use
   * @param hasAround whether this interceptor implements around advice
   * @param fieldMatcher the field matcher for FIELD advice mode, or null
   * @param catchExceptionTypeName the exception type name for CATCH advice mode, or null
   * @param aspectClassName the fully-qualified aspect class name this definition was derived from,
   *     or null for programmatically registered definitions; drives {@code @DeclarePrecedence}
   *     resolution
   * @since 2.0.0
   */
  public InterceptorDefinition(
      String name,
      Pointcut pointcut,
      Interceptor interceptor,
      int priority,
      AdviceMode adviceMode,
      boolean hasAround,
      com.github.cc11001100.weavergirl.api.matcher.FieldMatcher fieldMatcher,
      String catchExceptionTypeName,
      String aspectClassName) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("Interceptor name must not be null or empty");
    }
    if (pointcut == null) {
      throw new IllegalArgumentException("Pointcut must not be null for interceptor: " + name);
    }
    if (interceptor == null) {
      throw new IllegalArgumentException("Interceptor must not be null for interceptor: " + name);
    }
    if (adviceMode == null) {
      throw new IllegalArgumentException("AdviceMode must not be null for interceptor: " + name);
    }
    this.name = name;
    this.pointcut = pointcut;
    this.interceptor = interceptor;
    this.priority = priority;
    this.adviceMode = adviceMode;
    this.hasAround = hasAround;
    this.fieldMatcher = fieldMatcher;
    this.catchExceptionTypeName = catchExceptionTypeName;
    this.aspectClassName = aspectClassName;
  }

  /**
   * Returns the unique name of this interceptor definition.
   *
   * <p>The name is used as a key for unregistration via {@link
   * com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry#unregister(String)}.
   *
   * @return the definition name
   */
  public String getName() {
    return name;
  }

  /**
   * The plugin that registered this definition, when the registration happened while a plugin was
   * loading.
   *
   * <p>A plugin can register through any registry it can reach, not only the one its context handed it,
   * so the definition name is not a reliable way back to the plugin. The registry records the plugin
   * here as the definition is registered. It is not part of the definition's identity: two definitions
   * compare equal on their other fields whether or not it is set.
   *
   * @since 1.0.0
   */
  private transient Object owner;

  /** The plugin that registered this definition, or {@code null} when none was recorded. */
  public Object getOwner() {
    return owner;
  }

  /** Record the plugin that registered this definition. */
  public void setOwner(Object owner) {
    this.owner = owner;
  }

  /**
   * Returns the aspect class name associated with this definition, if any.
   *
   * <p>This is used for {@code @DeclarePrecedence} resolution: the precedence map is keyed by
   * fully-qualified aspect class names, and {@link
   * com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry#refreshPrecedence()}
   * loads the name with {@code Class.forName} to read the annotation. For definitions created by
   * {@link com.github.cc11001100.weavergirl.core.plugin.AnnotationPluginLoader} this returns the
   * fully-qualified aspect class name recorded at registration time. For programmatically
   * registered definitions it falls back to the definition name.
   *
   * @return the aspect class name, or the definition name if not available
   * @since 2.0.0
   */
  public String getAspectClassName() {
    // Explicit FQN set by AnnotationPluginLoader at registration time. This is the only value
    // that both loads via Class.forName and matches the precedence map keys users write in
    // @DeclarePrecedence ("com.example.MyAspect, ..."), so it must take priority.
    if (aspectClassName != null) {
      return aspectClassName;
    }
    // Heuristic fallback: AnnotationPluginLoader names definitions as
    // "annotation-" + aspectClassSimpleName + "-" + methodKey
    if (name.startsWith("annotation-")) {
      int firstDash = name.indexOf('-', "annotation-".length());
      if (firstDash > 0) {
        return name.substring("annotation-".length(), firstDash);
      }
    }
    return name;
  }

  /**
   * Returns the pointcut that determines which classes and methods this interceptor applies to.
   *
   * @return the pointcut, never null
   */
  public Pointcut getPointcut() {
    return pointcut;
  }

  /**
   * Returns the interceptor that is invoked when the pointcut matches.
   *
   * @return the interceptor, never null
   */
  public Interceptor getInterceptor() {
    return interceptor;
  }

  /**
   * Returns the execution priority of this interceptor definition.
   *
   * <p>Lower values indicate higher priority (executed first). The default is 0.
   *
   * @return the priority value
   */
  public int getPriority() {
    return priority;
  }

  /**
   * Returns the advice mode that selects which inlined advice class the transformer applies for
   * this definition.
   *
   * @return the advice mode, never null
   * @since 1.5.0
   */
  public AdviceMode getAdviceMode() {
    return adviceMode;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof InterceptorDefinition)) return false;
    InterceptorDefinition that = (InterceptorDefinition) o;
    return priority == that.priority
        && Objects.equals(name, that.name)
        && Objects.equals(pointcut, that.pointcut)
        && adviceMode == that.adviceMode;
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, pointcut, priority, adviceMode);
  }

  @Override
  public String toString() {
    return "InterceptorDefinition{name='"
        + name
        + "', pointcut="
        + pointcut
        + ", priority="
        + priority
        + ", adviceMode="
        + adviceMode
        + "}";
  }
}
