package com.github.cc11001100.weavergirl.api.pointcut;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;

public class PointcutExpression {

  public enum Type {
    EXECUTION,
    AT_ANNOTATION,
    AT_WITHIN,
    WITHIN,
    SUBCLASS_OF,
    IMPLEMENTING,
    AT_ARGS,
    AT_TARGET,
    CALL,
    HANDLER,
    AND,
    OR,
    NOT,
    CFLOW,
    IF
  }

  /**
   * Whether any {@code cflow()} expression has ever been built in this JVM.
   *
   * <p>An inlined advice pushes a frame on every woven method so a {@code cflow()} evaluated deeper
   * in the call stack can inspect its callers. When no cflow expression exists, nothing will ever
   * read that stack, so the push can be skipped entirely — it costs a string concatenation and
   * ThreadLocal work on every intercepted call. The flag is monotonic: once a cflow expression
   * exists, tracking stays on for the life of the process.
   */
  private static volatile boolean cflowTrackingRequired = false;

  private final Type type;
  private final String returnType;
  private final String classPattern;
  private final String methodPattern;
  private final String paramPattern;
  private final String annotationClassName;
  private final String packageName;
  private final String className;
  private final String interfaceName;
  private final PointcutExpression left;
  private final PointcutExpression right;
  private final String argumentAnnotationClassName;
  private final int[] argumentIndexes;
  private final String targetAnnotationClassName;
  private final String callReturnType;
  private final String callClassPattern;
  private final String callMethodPattern;
  private final String callParamPattern;
  private final String handlerExceptionType;
  private final PointcutExpression cflowExpression;
  private final String ifCondition;

  private PointcutExpression(
      Type type,
      String returnType,
      String classPattern,
      String methodPattern,
      String paramPattern,
      String annotationClassName,
      String packageName,
      String className,
      String interfaceName,
      PointcutExpression left,
      PointcutExpression right,
      String argumentAnnotationClassName,
      int[] argumentIndexes,
      String targetAnnotationClassName,
      String callReturnType,
      String callClassPattern,
      String callMethodPattern,
      String callParamPattern,
      String handlerExceptionType,
      PointcutExpression cflowExpression,
      String ifCondition) {
    this.type = type;
    this.returnType = returnType;
    this.classPattern = classPattern;
    this.methodPattern = methodPattern;
    this.paramPattern = paramPattern;
    this.annotationClassName = annotationClassName;
    this.packageName = packageName;
    this.className = className;
    this.interfaceName = interfaceName;
    this.left = left;
    this.right = right;
    this.argumentAnnotationClassName = argumentAnnotationClassName;
    this.argumentIndexes = argumentIndexes;
    this.targetAnnotationClassName = targetAnnotationClassName;
    this.callReturnType = callReturnType;
    this.callClassPattern = callClassPattern;
    this.callMethodPattern = callMethodPattern;
    this.callParamPattern = callParamPattern;
    this.handlerExceptionType = handlerExceptionType;
    this.cflowExpression = cflowExpression;
    this.ifCondition = ifCondition;
    if (type == Type.CFLOW) {
      cflowTrackingRequired = true;
    }
  }

  /**
   * Whether the woven advice must maintain the cflow call stack.
   *
   * <p>False until a {@code cflow()} expression is constructed; reading it is a single volatile
   * read, cheap enough for the per-invocation path.
   *
   * @return true when some cflow expression exists and call-stack tracking is needed
   * @since 1.7.0
   */
  public static boolean isCflowTrackingRequired() {
    return cflowTrackingRequired;
  }

  public static PointcutExpression execution(
      String returnType, String classPattern, String methodPattern, String paramPattern) {
    return new PointcutExpression(
        Type.EXECUTION,
        returnType,
        classPattern,
        methodPattern,
        paramPattern,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static PointcutExpression atAnnotation(String annotationClassName) {
    return new PointcutExpression(
        Type.AT_ANNOTATION,
        null,
        null,
        null,
        null,
        annotationClassName,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static PointcutExpression atWithin(String annotationClassName) {
    return new PointcutExpression(
        Type.AT_WITHIN,
        null,
        null,
        null,
        null,
        annotationClassName,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static PointcutExpression within(String packageName) {
    return new PointcutExpression(
        Type.WITHIN,
        null,
        null,
        null,
        null,
        null,
        packageName,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static PointcutExpression subclassOf(String className) {
    return new PointcutExpression(
        Type.SUBCLASS_OF,
        null,
        null,
        null,
        null,
        null,
        null,
        className,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static PointcutExpression implementing(String interfaceName) {
    return new PointcutExpression(
        Type.IMPLEMENTING,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        interfaceName,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static PointcutExpression atArgs(String annotationClassName, int... argumentIndexes) {
    return new PointcutExpression(
        Type.AT_ARGS,
        null,
        null,
        null,
        null,
        annotationClassName,
        null,
        null,
        null,
        null,
        null,
        annotationClassName,
        argumentIndexes,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static PointcutExpression atTarget(String annotationClassName) {
    return new PointcutExpression(
        Type.AT_TARGET,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        annotationClassName,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static PointcutExpression and(PointcutExpression left, PointcutExpression right) {
    return new PointcutExpression(
        Type.AND,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        left,
        right,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static PointcutExpression or(PointcutExpression left, PointcutExpression right) {
    return new PointcutExpression(
        Type.OR,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        left,
        right,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static PointcutExpression not(PointcutExpression expr) {
    return new PointcutExpression(
        Type.NOT,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        expr,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Creates a {@code call(...)} pointcut expression.
   *
   * <p><b>Semantics note (honest description):</b> this DSL keyword is currently an alias for
   * {@link #execution(String, String, String, String)}. In AspectJ, {@code call()} matches the
   * <i>call site</i> (the caller's invocation instruction) while {@code execution()} matches the
   * <i>callee's method body</i>. This framework weaves through {@code AgentBuilder} class/method
   * matchers, which operate on the method that is being executed — there is no call-site
   * interception, so {@code call(...)} is rewritten into the same execution-side matcher as
   * {@code execution(...)}.
   *
   * <p>Only {@code classPattern} and {@code methodPattern} participate in matching; {@code
   * returnType} and {@code paramPattern} are retained for display fidelity ({@link #toString()})
   * but are not validated against the target method.
   *
   * @param returnType the declared return type (retained for display only)
   * @param classPattern the class name pattern of the callee
   * @param methodPattern the method name pattern
   * @param paramPattern the parameter signature (retained for display only)
   * @return a CALL pointcut expression
   * @since 1.0.0
   */
  public static PointcutExpression call(
      String returnType, String classPattern, String methodPattern, String paramPattern) {
    return new PointcutExpression(
        Type.CALL,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        returnType,
        classPattern,
        methodPattern,
        paramPattern,
        null,
        null,
        null);
  }

  /**
   * Creates a {@code handler(...)} pointcut expression.
   *
   * <p><b>Semantics note (honest description):</b> this DSL keyword is a placeholder. In AspectJ,
   * {@code handler(TypePattern)} matches exception-handler join points (catch blocks). This
   * framework has no catch-block weaving strategy yet, so {@code handler(...)} does <b>not</b>
   * match exception handlers. The current implementation matches any method literally named
   * {@code "handler"} in any class; {@code exceptionType} is retained for display fidelity
   * ({@link #toString()}) but does not participate in matching.
   *
   * @param exceptionType the exception type (retained for display only)
   * @return a HANDLER pointcut expression
   * @since 1.0.0
   */
  public static PointcutExpression handler(String exceptionType) {
    return new PointcutExpression(
        Type.HANDLER,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        exceptionType,
        null,
        null);
  }

  public static PointcutExpression cflow(PointcutExpression expression) {
    return new PointcutExpression(
        Type.CFLOW,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        expression,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        expression,
        null);
  }

  public static PointcutExpression ifCondition(String condition) {
    return new PointcutExpression(
        Type.IF,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        condition);
  }

  public Type getType() {
    return type;
  }

  public String getReturnType() {
    return returnType;
  }

  public String getClassPattern() {
    return classPattern;
  }

  public String getMethodPattern() {
    return methodPattern;
  }

  public String getParamPattern() {
    return paramPattern;
  }

  public String getAnnotationClassName() {
    return annotationClassName;
  }

  public String getPackageName() {
    return packageName;
  }

  public String getClassName() {
    return className;
  }

  public String getInterfaceName() {
    return interfaceName;
  }

  public PointcutExpression getLeft() {
    return left;
  }

  public PointcutExpression getRight() {
    return right;
  }

  public PointcutExpression getOperand() {
    return left;
  }

  public String getArgumentAnnotationClassName() {
    return argumentAnnotationClassName;
  }

  public int[] getArgumentIndexes() {
    return argumentIndexes;
  }

  public String getTargetAnnotationClassName() {
    return targetAnnotationClassName;
  }

  public String getCallReturnType() {
    return callReturnType;
  }

  public String getCallClassPattern() {
    return callClassPattern;
  }

  public String getCallMethodPattern() {
    return callMethodPattern;
  }

  public String getCallParamPattern() {
    return callParamPattern;
  }

  public String getHandlerExceptionType() {
    return handlerExceptionType;
  }

  public PointcutExpression getCflowExpression() {
    return cflowExpression;
  }

  public String getIfCondition() {
    return ifCondition;
  }

  /**
   * Evaluate whether this pointcut's runtime conditions match the current invocation context.
   *
   * <p>This is used for {@link Type#CFLOW} and {@link Type#IF} expressions, which cannot be
   * evaluated purely from static class/method matchers.
   *
   * @param className the current class name
   * @param methodName the current method name
   * @param arguments the method arguments
   * @param returnValue the return value (may be null)
   * @param throwable the thrown exception (may be null)
   * @return true if the runtime condition matches
   * @since 1.0.0
   */
  public boolean evaluateRuntimeCondition(
      String className,
      String methodName,
      Object[] arguments,
      Object returnValue,
      Throwable throwable) {
    switch (type) {
      case CFLOW:
        return evaluateCflow(className, methodName);
      case IF:
        return evaluateIf(arguments, returnValue, throwable);
      case AND:
        return left.evaluateRuntimeCondition(className, methodName, arguments, returnValue, throwable)
            && right.evaluateRuntimeCondition(className, methodName, arguments, returnValue, throwable);
      case OR:
        return left.evaluateRuntimeCondition(className, methodName, arguments, returnValue, throwable)
            || right.evaluateRuntimeCondition(className, methodName, arguments, returnValue, throwable);
      case NOT:
        return !left.evaluateRuntimeCondition(className, methodName, arguments, returnValue, throwable);
      default:
        // For static matchers (execution, @annotation, etc.), always match
        return true;
    }
  }

  private boolean evaluateCflow(String className, String methodName) {
    PointcutExpression inner = cflowExpression;
    if (inner == null) {
      return true;
    }
    // Check if the inner pointcut matches any frame in the current call stack,
    // excluding the topmost frame (the current method being evaluated).
    // This implements AspectJ-compatible cflow semantics: cflow(expr) matches
    // join points that are executed WITHIN the control flow of expr, meaning
    // expr matches some CALLER of the current join point, not the join point itself.
    CflowContext ctx = CflowContext.current();
    if (ctx == null) {
      return false;
    }
    return ctx.matchesExcludingTop(inner, className, methodName);
  }

  private boolean evaluateIf(Object[] arguments, Object returnValue, Throwable throwable) {
    String condition = ifCondition;
    if (condition == null || condition.isEmpty()) {
      return true;
    }
    // Simple if() evaluation: support basic expressions on args/return/exception.
    // This is intentionally lightweight; for complex conditions users should use @Around.
    String trimmed = condition.trim();
    if (trimmed.startsWith("args.length > ")) {
      try {
        int threshold = Integer.parseInt(trimmed.substring("args.length > ".length()).trim());
        return arguments != null && arguments.length > threshold;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    if (trimmed.startsWith("args.length >= ")) {
      try {
        int threshold = Integer.parseInt(trimmed.substring("args.length >= ".length()).trim());
        return arguments != null && arguments.length >= threshold;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    if ("args != null".equals(trimmed)) {
      return arguments != null;
    }
    if ("args == null".equals(trimmed)) {
      return arguments == null;
    }
    if (trimmed.startsWith("args[") && trimmed.contains("!= null")) {
      // Simple arg index != null check
      int start = trimmed.indexOf('[') + 1;
      int end = trimmed.indexOf(']');
      if (start > 0 && end > start) {
        try {
          int idx = Integer.parseInt(trimmed.substring(start, end));
          return arguments != null && idx >= 0 && idx < arguments.length && arguments[idx] != null;
        } catch (NumberFormatException e) {
          return false;
        }
      }
    }
    if (trimmed.startsWith("result != null") || trimmed.startsWith("return != null")) {
      return returnValue != null;
    }
    if (trimmed.startsWith("exception != null")) {
      return throwable != null;
    }
    // Unknown condition: default to true to avoid silently disabling advice
    return true;
  }

  /**
   * ThreadLocal cflow context that tracks matched call stacks for cflow evaluation.
   *
   * <p>This is a minimal implementation that uses a ThreadLocal stack of (className, methodName)
   * pairs. For full AspectJ-compatible cflow, this would need to track the full call stack and
   * support intersection/union of cflow expressions.
   *
   * @since 1.0.0
   */
  public static final class CflowContext {
    private static final ThreadLocal<CflowContext> CURRENT = new ThreadLocal<>();

    /**
     * Class and method names of the frames on this thread, most recent first. Two parallel stacks
     * instead of one of pre-concatenated strings: a push then costs no allocation, and cflow is
     * evaluated on every intercepted call whenever any cflow expression exists.
     */
    private final java.util.Deque<String> classes = new java.util.ArrayDeque<>();

    private final java.util.Deque<String> methods = new java.util.ArrayDeque<>();

    static CflowContext current() {
      return CURRENT.get();
    }

    static void enter(String className, String methodName) {
      CflowContext ctx = CURRENT.get();
      if (ctx == null) {
        ctx = new CflowContext();
        CURRENT.set(ctx);
      }
      ctx.classes.push(className);
      ctx.methods.push(methodName);
    }

    static void exit() {
      CflowContext ctx = CURRENT.get();
      if (ctx != null && !ctx.classes.isEmpty()) {
        ctx.classes.pop();
        ctx.methods.pop();
        if (ctx.classes.isEmpty()) {
          CURRENT.remove();
        }
      }
    }

    /** Whether the most recent frame is the given join point. */
    boolean topIs(String className, String methodName) {
      return !classes.isEmpty()
          && classes.peek().equals(className)
          && methods.peek().equals(methodName);
    }

    boolean matches(PointcutExpression inner, String className, String methodName) {
      // Check if the inner pointcut matches any frame in the call stack
      Pointcut innerPointcut = inner.toPointcut();
      java.util.Iterator<String> classIt = classes.iterator();
      java.util.Iterator<String> methodIt = methods.iterator();
      while (classIt.hasNext() && methodIt.hasNext()) {
        if (innerPointcut.matches(classIt.next(), methodIt.next())) {
          return true;
        }
      }
      return false;
    }

    boolean matchesExcludingTop(PointcutExpression inner, String currentClass, String currentMethod) {
      // AspectJ cflow semantics: the inner pointcut should match some CALLER of the current
      // join point, not the current join point itself.
      // The top of the call stack (most recent frame) is the current method being evaluated,
      // so we skip it and check only the caller frames below it.
      Pointcut innerPointcut = inner.toPointcut();
      java.util.Iterator<String> classIt = classes.iterator();
      java.util.Iterator<String> methodIt = methods.iterator();
      // Skip the first frame (the current method's own frame)
      if (classIt.hasNext()) {
        classIt.next();
        methodIt.next();
      }
      while (classIt.hasNext() && methodIt.hasNext()) {
        if (innerPointcut.matches(classIt.next(), methodIt.next())) {
          return true;
        }
      }
      return false;
    }
  }

  public static boolean enterCflow(String className, String methodName) {
    PointcutExpression.CflowContext ctx = PointcutExpression.CflowContext.current();
    if (ctx == null) {
      ctx = new PointcutExpression.CflowContext();
      PointcutExpression.CflowContext.CURRENT.set(ctx);
    }
    ctx.classes.push(className);
    ctx.methods.push(methodName);
    return true;
  }

  /**
   * Pops the frame for the given join point, if it is the most recent one.
   *
   * <p>The ownership check matters when call-stack tracking is switched on while a call is already
   * in flight: an invocation that entered without pushing must not pop the frame of a caller, or
   * every enclosing cflow evaluation would see a corrupted stack. A frame that does not match is
   * left in place.
   *
   * @param className the class whose frame should be popped
   * @param methodName the method whose frame should be popped
   */
  public static void exitCflow(String className, String methodName) {
    PointcutExpression.CflowContext ctx = PointcutExpression.CflowContext.current();
    if (ctx != null && ctx.topIs(className, methodName)) {
      PointcutExpression.CflowContext.exit();
    }
  }

  /** Drops every cflow frame recorded on this thread. Intended for tests and for a thread that
   * abandoned a request mid-flight. */
  public static void resetCflow() {
    PointcutExpression.CflowContext.CURRENT.remove();
  }

  public Pointcut toPointcut() {
    switch (type) {
      case EXECUTION:
        return new Pointcut(buildClassMatcher(), buildMethodMatcher(), this);
      case AT_ANNOTATION:
        return new Pointcut(
            ClassMatcher.byNamePattern(".*"), MethodMatcher.byAnnotation(annotationClassName), this);
      case AT_WITHIN:
        return new Pointcut(ClassMatcher.byAnnotation(annotationClassName), MethodMatcher.any(), this);
      case WITHIN:
        return new Pointcut(
            ClassMatcher.byNamePattern(convertToRegex(packageName)), MethodMatcher.any(), this);
      case SUBCLASS_OF:
        return new Pointcut(ClassMatcher.bySuperClass(className), MethodMatcher.any(), this);
      case IMPLEMENTING:
        return new Pointcut(ClassMatcher.byInterface(interfaceName), MethodMatcher.any(), this);
      case AT_ARGS:
        return new Pointcut(
            ClassMatcher.any(),
            MethodMatcher.byArgumentAnnotation(annotationClassName, argumentIndexes),
            this);
      case AT_TARGET:
        return new Pointcut(
            ClassMatcher.byAnnotation(targetAnnotationClassName), MethodMatcher.any(), this);
      case CALL:
        // call() is an alias for execution() in this framework (see the call() factory javadoc):
        // there is no call-site weaving, so we build the same exact/pattern matchers as
        // execution() from the callee's class and method patterns.
        return new Pointcut(
            buildClassMatcher(callClassPattern), buildMethodMatcher(callMethodPattern), this);
      case HANDLER:
        // Placeholder semantics (see the handler() factory javadoc): no catch-block weaving
        // exists yet, so this matches any method literally named "handler"; exceptionType is
        // not consulted.
        return new Pointcut(ClassMatcher.any(), MethodMatcher.byName("handler"), this);
      case CFLOW:
        // cflow is a runtime condition, not a static class/method matcher.
        // Return an always-matching pointcut and let the runtime evaluator decide.
        return new Pointcut(ClassMatcher.any(), MethodMatcher.any(), this);
      case IF:
        // if() is a runtime boolean condition, not a static matcher.
        return new Pointcut(ClassMatcher.any(), MethodMatcher.any(), this);
      case AND:
        return left.toPointcut().and(right.toPointcut());
      case OR:
        return left.toPointcut().or(right.toPointcut());
      case NOT:
        return left.toPointcut().negate();
      default:
        throw new IllegalStateException("Unknown expression type: " + type);
    }
  }

  public Pointcut toPointcutWithClassScope(ClassMatcher classScope) {
    switch (type) {
      case EXECUTION:
        return new Pointcut(buildClassMatcher(), buildMethodMatcher(), this);
      case AT_ANNOTATION:
        return new Pointcut(classScope, MethodMatcher.byAnnotation(annotationClassName), this);
      case AT_WITHIN:
        return new Pointcut(ClassMatcher.byAnnotation(annotationClassName), MethodMatcher.any(), this);
      case WITHIN:
        return new Pointcut(
            ClassMatcher.byNamePattern(convertToRegex(packageName)), MethodMatcher.any(), this);
      case SUBCLASS_OF:
        return new Pointcut(ClassMatcher.bySuperClass(className), MethodMatcher.any(), this);
      case IMPLEMENTING:
        return new Pointcut(ClassMatcher.byInterface(interfaceName), MethodMatcher.any(), this);
      case AT_ARGS:
        return new Pointcut(
            ClassMatcher.any(),
            MethodMatcher.byArgumentAnnotation(annotationClassName, argumentIndexes),
            this);
      case AT_TARGET:
        return new Pointcut(
            ClassMatcher.byAnnotation(targetAnnotationClassName), MethodMatcher.any(), this);
      case CALL:
        // call() is an alias for execution() in this framework (see the call() factory javadoc):
        // there is no call-site weaving, so we build the same exact/pattern matchers as
        // execution() from the callee's class and method patterns.
        return new Pointcut(
            buildClassMatcher(callClassPattern), buildMethodMatcher(callMethodPattern), this);
      case HANDLER:
        // Placeholder semantics (see the handler() factory javadoc): no catch-block weaving
        // exists yet, so this matches any method literally named "handler"; exceptionType is
        // not consulted.
        return new Pointcut(ClassMatcher.any(), MethodMatcher.byName("handler"), this);
      case CFLOW:
      case IF:
        return new Pointcut(ClassMatcher.any(), MethodMatcher.any(), this);
      case AND:
        return left.toPointcutWithClassScope(classScope)
            .and(right.toPointcutWithClassScope(classScope));
      case OR:
        return left.toPointcutWithClassScope(classScope)
            .or(right.toPointcutWithClassScope(classScope));
      case NOT:
        return left.toPointcutWithClassScope(classScope).negate();
      default:
        throw new IllegalStateException("Unknown expression type: " + type);
    }
  }

  private ClassMatcher buildClassMatcher() {
    return buildClassMatcher(classPattern);
  }

  private ClassMatcher buildClassMatcher(String pattern) {
    if (pattern == null || pattern.isEmpty()) {
      return ClassMatcher.byNamePattern(".*");
    }
    if (containsWildcard(pattern)) {
      return ClassMatcher.byNamePattern(convertToRegex(pattern));
    }
    return ClassMatcher.byName(pattern);
  }

  private MethodMatcher buildMethodMatcher() {
    return buildMethodMatcher(methodPattern);
  }

  private MethodMatcher buildMethodMatcher(String pattern) {
    if (pattern == null || pattern.isEmpty()) {
      return MethodMatcher.any();
    }
    if (containsWildcard(pattern)) {
      return MethodMatcher.byNamePattern(convertToRegex(pattern));
    }
    return MethodMatcher.byName(pattern);
  }

  static String convertToRegex(String pattern) {
    if (pattern == null || pattern.isEmpty()) {
      return ".*";
    }
    String regex = pattern;
    regex = regex.replace("\\", "\\\\");
    regex = regex.replace("[", "\\[");
    regex = regex.replace("]", "\\]");
    regex = regex.replace("(", "\\(");
    regex = regex.replace(")", "\\)");
    regex = regex.replace("{", "\\{");
    regex = regex.replace("}", "\\}");
    regex = regex.replace("+", "\\+");
    regex = regex.replace("?", "\\?");
    regex = regex.replace("^", "\\^");
    regex = regex.replace("$", "\\$");
    regex = regex.replace("|", "\\|");
    regex = regex.replace("..", "PLACEHOLDER_DOUBLE_DOT");
    regex = regex.replace("*", "[^.]*");
    regex = regex.replace("PLACEHOLDER_DOUBLE_DOT", ".*");
    return regex;
  }

  private static boolean containsWildcard(String pattern) {
    return pattern.contains("*") || pattern.contains("..");
  }

  @Override
  public String toString() {
    switch (type) {
      case EXECUTION:
        return "execution("
            + returnType
            + " "
            + classPattern
            + "."
            + methodPattern
            + "("
            + paramPattern
            + "))";
      case AT_ANNOTATION:
        return "@annotation(" + annotationClassName + ")";
      case AT_WITHIN:
        return "@within(" + annotationClassName + ")";
      case WITHIN:
        return "within(" + packageName + ")";
      case SUBCLASS_OF:
        return "subclassOf(" + className + ")";
      case IMPLEMENTING:
        return "implementing(" + interfaceName + ")";
      case AT_ARGS:
        return "@args("
            + argumentAnnotationClassName
            + ", "
            + java.util.Arrays.toString(argumentIndexes)
            + ")";
      case AT_TARGET:
        return "@target(" + targetAnnotationClassName + ")";
      case CALL:
        return "call("
            + (callReturnType != null ? callReturnType + " " : "")
            + callClassPattern
            + "."
            + callMethodPattern
            + "("
            + (callParamPattern != null ? callParamPattern : "..")
            + "))";
      case HANDLER:
        return "handler(" + handlerExceptionType + ")";
      case AND:
        return "(" + left + " && " + right + ")";
      case OR:
        return "(" + left + " || " + right + ")";
      case NOT:
        return "!(" + left + ")";
      default:
        return "PointcutExpression{type=" + type + "}";
    }
  }
}
