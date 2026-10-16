package com.github.cc11001100.weavergirl.api.pointcut;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import java.util.Objects;

/**
 * Combines a {@link ClassMatcher} and {@link MethodMatcher} to define which classes and methods
 * should be intercepted.
 *
 * <p>A pointcut is the "where" of interception: it selects a set of join points (method executions)
 * by matching on class and method criteria. The "what" to do at those join points is defined by an
 * {@link com.github.cc11001100.weavergirl.api.interceptor.Interceptor}, bound together in an {@link
 * com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition}.
 *
 * <h3>Composition</h3>
 *
 * <p>Pointcuts can be composed using the {@link #and(Pointcut)} and {@link #or(Pointcut)} methods
 * to create complex matching logic without resorting to elaborate regex patterns.
 *
 * <h3>Usage example</h3>
 *
 * <pre>
 * Pointcut simple = new Pointcut(
 *     ClassMatcher.byName("com.example.Service"),
 *     MethodMatcher.byName("process")
 * );
 *
 * // Compose pointcuts
 * Pointcut composite = new Pointcut(
 *     ClassMatcher.byNamePattern("com\\.example\\..*"),
 *     MethodMatcher.byNamePattern("process.*")
 * ).or(new Pointcut(
 *     ClassMatcher.byAnnotation("com.example.Traced"),
 *     MethodMatcher.any()
 * ));</pre>
 *
 * @see ClassMatcher
 * @see MethodMatcher
 * @see com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition
 * @since 1.0.0
 */
public class Pointcut {

  private final ClassMatcher classMatcher;
  private final MethodMatcher methodMatcher;
  private final com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression expression;
  private final boolean instanceBound;

  /**
   * Creates a new pointcut with the given class and method matchers.
   *
   * @param classMatcher matcher for selecting target classes
   * @param methodMatcher matcher for selecting target methods within matched classes
   */
  public Pointcut(ClassMatcher classMatcher, MethodMatcher methodMatcher) {
    this(classMatcher, methodMatcher, null, false);
  }

  /**
   * Creates a new pointcut with the given class and method matchers and an optional runtime
   * expression for conditional pointcuts such as cflow/if.
   *
   * @param classMatcher matcher for selecting target classes
   * @param methodMatcher matcher for selecting target methods within matched classes
   * @param expression the optional runtime expression, may be null
   * @since 2.0.0
   */
  public Pointcut(
      ClassMatcher classMatcher,
      MethodMatcher methodMatcher,
      com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression expression) {
    this(classMatcher, methodMatcher, expression, false);
  }

  /**
   * Creates a new pointcut with all fields specified, including instance-binding for pertarget/perthis.
   *
   * @param classMatcher matcher for selecting target classes
   * @param methodMatcher matcher for selecting target methods within matched classes
   * @param expression the optional runtime expression, may be null
   * @param instanceBound true if this pointcut is bound to a specific instance (pertarget/perthis)
   * @since 2.0.0
   */
  public Pointcut(
      ClassMatcher classMatcher,
      MethodMatcher methodMatcher,
      com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression expression,
      boolean instanceBound) {
    this.classMatcher = classMatcher;
    this.methodMatcher = methodMatcher;
    this.expression = expression;
    this.instanceBound = instanceBound;
  }

  /**
   * Returns the runtime expression associated with this pointcut, if any.
   *
   * @return the expression, or null if none
   * @since 2.0.0
   */
  public com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression getExpression() {
    return expression;
  }

  /**
   * Returns whether this pointcut is instance-bound for pertarget/perthis advice.
   *
   * @return true if this pointcut is bound to a specific instance
   * @since 2.0.0
   */
  public boolean isInstanceBound() {
    return instanceBound;
  }

  /**
   * Returns the class matcher component of this pointcut.
   *
   * @return the class matcher, never null
   */
  public ClassMatcher getClassMatcher() {
    return classMatcher;
  }

  /**
   * Returns the method matcher component of this pointcut.
   *
   * @return the method matcher, never null
   */
  public MethodMatcher getMethodMatcher() {
    return methodMatcher;
  }

  /**
   * Checks whether the given class and method names match this pointcut.
   *
   * <p>Both the class matcher and method matcher must match for this method to return {@code true}.
   *
   * <p>This performs <strong>static</strong> matching only. Runtime conditions such as {@code
   * cflow()} or {@code if()} are evaluated by {@link #matchRuntime(String, String, Class[],
   * Object[], Object, Throwable)}.
   *
   * @param className the fully-qualified class name to test
   * @param methodName the method name to test
   * @return true if both the class and method matchers match
   */
  public boolean matches(String className, String methodName) {
    return classMatcher.matches(className) && methodMatcher.matches(methodName);
  }

  /**
   * Full join-point matching: static method matcher AND any runtime condition (e.g. {@code
   * cflow()}/{@code if()}) evaluated together.
   *
   * <p><strong>Class matching is intentionally not re-checked here.</strong> The core engine
   * (ByteBuddy) has already selected the class at instrumentation time, so a method that was woven
   * necessarily passed its {@link ClassMatcher}. Re-checking it at dispatch time would wrongly
   * reject {@code SUPER_CLASS}/{@code INTERFACE}/{@code ANNOTATION} matchers, which cannot be
   * decided from a bare class name. {@code className} is still passed through for runtime
   * expressions (e.g. {@code cflow()} frame matching) to use.
   *
   * <p>When {@code parameterTypes} is non-null, methods are matched by name <em>and</em> signature
   * (used by constructor interception); otherwise only the method name is matched.
   *
   * <p>Composed pointcuts built via {@link #and(Pointcut)}, {@link #or(Pointcut)} and {@link
   * #negate()} override this method to evaluate each operand self-containedly (method match plus
   * its own runtime conditions). This keeps the decomposition exact: {@code (m1 && r1) || (m2 &&
   * r2)} is evaluated as such, rather than as the unsound {@code (m1 || m2) && (r1 || r2)}.
   *
   * <p>The base implementation returns {@code true} when the pointcut carries no runtime condition
   * (the common case), so there is no evaluation cost beyond the static {@link
   * MethodMatcher#matches(String)} check.
   *
   * @param className the fully-qualified class name (used by runtime expressions only)
   * @param methodName the method name
   * @param parameterTypes the parameter types for signature matching, or null to match by name only
   * @param arguments the method arguments
   * @param returnValue the return value (may be null, e.g. on method entry)
   * @param throwable the thrown exception (may be null)
   * @return true if this pointcut matches the method and any runtime condition is satisfied
   * @since 2.0.0
   */
  public boolean matchRuntime(
      String className,
      String methodName,
      Class<?>[] parameterTypes,
      Object[] arguments,
      Object returnValue,
      Throwable throwable) {
    if (parameterTypes != null) {
      if (!methodMatcher.matches(methodName, parameterTypes)) {
        return false;
      }
    } else if (!methodMatcher.matches(methodName)) {
      return false;
    }
    com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression expr = expression;
    return expr == null
        || expr.evaluateRuntimeCondition(className, methodName, arguments, returnValue, throwable);
  }

  /**
   * Creates a pointcut that matches if and only if <em>both</em> this pointcut and the other
   * pointcut match.
   *
   * <p>The returned pointcut overrides {@link #matches(String, String)} to evaluate both pointcuts
   * and return their logical AND, and {@link #matchRuntime(String, String, Class[], Object[],
   * Object, Throwable)} to evaluate <em>both operands self-containedly</em> (static match plus any
   * {@code cflow()}/{@code if()} runtime conditions each may carry).
   *
   * <p>When combining matchers, if one side uses {@link MethodMatcher.MatchType#ANY}, the composite
   * inherits the other side's matcher. This preserves specific matchers like {@link
   * MethodMatcher.MatchType#ARGS} across composition.
   *
   * <p>Runtime conditions are <strong>not</strong> combined into a single {@link
   * PointcutExpression}: each operand keeps its own expression and is evaluated independently via
   * {@link #matchRuntime(String, String, Class[], Object[], Object, Throwable)}. This keeps the
   * decomposition {@code (m1 && r1) && (m2 && r2)} exact even when one operand is a pure static
   * matcher and the other carries {@code cflow()}/{@code if()}.
   *
   * @param other the pointcut to AND with this one
   * @return a new composite pointcut
   */
  public Pointcut and(Pointcut other) {
    ClassMatcher combinedClass = combineClassMatcher(this.classMatcher, other.classMatcher);
    MethodMatcher combinedMethod = combineMethodMatcher(this.methodMatcher, other.methodMatcher);
    return new Pointcut(combinedClass, combinedMethod) {
      @Override
      public boolean matches(String className, String methodName) {
        return Pointcut.this.matches(className, methodName) && other.matches(className, methodName);
      }

      @Override
      public boolean matchRuntime(
          String className,
          String methodName,
          Class<?>[] parameterTypes,
          Object[] arguments,
          Object returnValue,
          Throwable throwable) {
        return Pointcut.this.matchRuntime(
                className, methodName, parameterTypes, arguments, returnValue, throwable)
            && other.matchRuntime(
                className, methodName, parameterTypes, arguments, returnValue, throwable);
      }
    };
  }

  /**
   * Creates a pointcut that matches if <em>either</em> this pointcut or the other pointcut matches.
   *
   * <p>The returned pointcut overrides {@link #matches(String, String)} to evaluate both pointcuts
   * and return their logical OR, and {@link #matchRuntime(String, String, Class[], Object[],
   * Object, Throwable)} to evaluate <em>both operands self-containedly</em> as {@code (m1 && r1) ||
   * (m2 && r2)}.
   *
   * <p>When combining matchers, if one side uses {@link MethodMatcher.MatchType#ANY}, the composite
   * inherits the other side's matcher.
   *
   * <p>Runtime conditions are <strong>not</strong> combined into a single {@link
   * PointcutExpression}: each operand keeps its own expression and is evaluated independently via
   * {@link #matchRuntime(String, String, Class[], Object[], Object, Throwable)}, so the OR
   * decomposition stays exact.
   *
   * @param other the pointcut to OR with this one
   * @return a new composite pointcut
   */
  public Pointcut or(Pointcut other) {
    ClassMatcher combinedClass = combineClassMatcher(this.classMatcher, other.classMatcher);
    MethodMatcher combinedMethod = combineMethodMatcher(this.methodMatcher, other.methodMatcher);
    return new Pointcut(combinedClass, combinedMethod) {
      @Override
      public boolean matches(String className, String methodName) {
        return Pointcut.this.matches(className, methodName) || other.matches(className, methodName);
      }

      @Override
      public boolean matchRuntime(
          String className,
          String methodName,
          Class<?>[] parameterTypes,
          Object[] arguments,
          Object returnValue,
          Throwable throwable) {
        return Pointcut.this.matchRuntime(
                className, methodName, parameterTypes, arguments, returnValue, throwable)
            || other.matchRuntime(
                className, methodName, parameterTypes, arguments, returnValue, throwable);
      }
    };
  }

  private static ClassMatcher combineClassMatcher(ClassMatcher a, ClassMatcher b) {
    if (a.getMatchType() == ClassMatcher.MatchType.ANY) return b;
    if (b.getMatchType() == ClassMatcher.MatchType.ANY) return a;
    return a;
  }

  private static MethodMatcher combineMethodMatcher(MethodMatcher a, MethodMatcher b) {
    if (a.getMatchType() == MethodMatcher.MatchType.ANY) return b;
    if (b.getMatchType() == MethodMatcher.MatchType.ANY) return a;
    return a;
  }

  /**
   * Creates a negated version of this pointcut. Matches when this pointcut does NOT match.
   *
   * <p>The returned pointcut overrides {@link #matches(String, String)} and {@link
   * #matchRuntime(String, String, Class[], Object[], Object, Throwable)} to negate the operand's
   * evaluation.
   *
   * @return a new Pointcut that negates this one
   */
  public Pointcut negate() {
    return new Pointcut(this.classMatcher, this.methodMatcher) {
      @Override
      public boolean matches(String className, String methodName) {
        return !Pointcut.this.matches(className, methodName);
      }

      @Override
      public boolean matchRuntime(
          String className,
          String methodName,
          Class<?>[] parameterTypes,
          Object[] arguments,
          Object returnValue,
          Throwable throwable) {
        return !Pointcut.this.matchRuntime(
            className, methodName, parameterTypes, arguments, returnValue, throwable);
      }
    };
  }

  // --- pertarget / perthis instance binding ---

  /**
   * Bind this pointcut to the target instance of the intercepted method.
   *
   * @return this pointcut, marked as instance-bound
   * @since 2.0.0
   */
  public Pointcut pertarget() {
    return new Pointcut(this.classMatcher, this.methodMatcher, this.expression, true);
  }

  /**
   * Bind this pointcut to the proxy instance of the intercepted method.
   *
   * @return this pointcut, marked as instance-bound
   * @since 2.0.0
   */
  public Pointcut perthis() {
    return new Pointcut(this.classMatcher, this.methodMatcher, this.expression, true);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Pointcut)) return false;
    Pointcut that = (Pointcut) o;
    return Objects.equals(classMatcher, that.classMatcher)
        && Objects.equals(methodMatcher, that.methodMatcher)
        && instanceBound == that.instanceBound;
  }

  @Override
  public int hashCode() {
    return Objects.hash(classMatcher, methodMatcher, instanceBound);
  }

  @Override
  public String toString() {
    return "Pointcut{class=" + classMatcher + ", method=" + methodMatcher + "}";
  }
}
