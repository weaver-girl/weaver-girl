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
   * @since 2.0.0
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
    // Check if the inner pointcut matches anywhere in the current call stack.
    // We use a ThreadLocal to track the cflow context across the stack.
    CflowContext ctx = CflowContext.current();
    if (ctx == null) {
      return false;
    }
    return ctx.matches(inner, className, methodName);
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
   * @since 2.0.0
   */
  public static final class CflowContext {
    private static final ThreadLocal<CflowContext> CURRENT = new ThreadLocal<>();

    private final java.util.Deque<String> callStack = new java.util.ArrayDeque<>();

    static CflowContext current() {
      return CURRENT.get();
    }

    static void enter(String className, String methodName) {
      CflowContext ctx = CURRENT.get();
      if (ctx == null) {
        ctx = new CflowContext();
        CURRENT.set(ctx);
      }
      ctx.callStack.push(className + "." + methodName);
    }

    static void exit() {
      CflowContext ctx = CURRENT.get();
      if (ctx != null && !ctx.callStack.isEmpty()) {
        ctx.callStack.pop();
        if (ctx.callStack.isEmpty()) {
          CURRENT.remove();
        }
      }
    }

    boolean matches(PointcutExpression inner, String className, String methodName) {
      // Check if the inner pointcut matches any frame in the call stack
      Pointcut innerPointcut = inner.toPointcut();
      for (String frame : callStack) {
        int dot = frame.lastIndexOf('.');
        if (dot < 0) continue;
        String frameClass = frame.substring(0, dot);
        String frameMethod = frame.substring(dot + 1);
        if (innerPointcut.matches(frameClass, frameMethod)) {
          return true;
        }
      }
      return false;
    }
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
        return new Pointcut(
            ClassMatcher.byNamePattern(convertToRegex(callClassPattern)),
            MethodMatcher.byNamePattern(convertToRegex(callMethodPattern)),
            this);
      case HANDLER:
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
        return new Pointcut(
            ClassMatcher.byNamePattern(convertToRegex(callClassPattern)),
            MethodMatcher.byNamePattern(convertToRegex(callMethodPattern)),
            this);
      case HANDLER:
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
    if (classPattern == null || classPattern.isEmpty()) {
      return ClassMatcher.byNamePattern(".*");
    }
    if (containsWildcard(classPattern)) {
      return ClassMatcher.byNamePattern(convertToRegex(classPattern));
    }
    return ClassMatcher.byName(classPattern);
  }

  private MethodMatcher buildMethodMatcher() {
    if (methodPattern == null || methodPattern.isEmpty()) {
      return MethodMatcher.any();
    }
    if (containsWildcard(methodPattern)) {
      return MethodMatcher.byNamePattern(convertToRegex(methodPattern));
    }
    return MethodMatcher.byName(methodPattern);
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
