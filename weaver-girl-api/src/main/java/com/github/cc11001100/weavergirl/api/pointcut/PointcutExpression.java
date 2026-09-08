package com.github.cc11001100.weavergirl.api.pointcut;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;

/**
 * Data model for a parsed pointcut expression.
 *
 * <p>Represents a single pointcut designator (e.g., {@code execution(...)},
 * {@code @annotation(...)}, {@code within(...)}) or a composite expression formed with {@code &&}
 * or {@code ||} operators.
 *
 * <h3>Supported types</h3>
 *
 * <ul>
 *   <li>{@link Type#EXECUTION} &mdash; method execution pointcut
 *   <li>{@link Type#AT_ANNOTATION} &mdash; method annotation pointcut
 *   <li>{@link Type#AT_WITHIN} &mdash; class annotation pointcut
 *   <li>{@link Type#WITHIN} &mdash; package/class scope pointcut
 *   <li>{@link Type#SUBCLASS_OF} &mdash; subclass matching pointcut
 *   <li>{@link Type#IMPLEMENTING} &mdash; interface implementation pointcut
 *   <li>{@link Type#AND} / {@link Type#OR} &mdash; composite expressions
 *   <li>{@link Type#NOT} &mdash; logical negation
 * </ul>
 *
 * <h3>Pattern conversion</h3>
 *
 * <p>Wildcard patterns are converted to Java regex:
 *
 * <ul>
 *   <li>{@code ..} (double-dot) &rarr; {@code .*} (match any package segments)
 *   <li>{@code *} (single asterisk) &rarr; {@code [^.]*} (match a single segment)
 * </ul>
 *
 * @see PointcutParser
 * @see Pointcut
 * @since 1.1.0
 */
public class PointcutExpression {

  /** The type of pointcut expression. */
  public enum Type {
    /** Method execution: {@code execution(retType classPattern.methodPattern(params))}. */
    EXECUTION,
    /** Method-level annotation: {@code @annotation(className)}. */
    AT_ANNOTATION,
    /** Class-level annotation: {@code @within(className)}. */
    AT_WITHIN,
    /** Package/class scope: {@code within(packagePattern)}. */
    WITHIN,
    /** Subclass matching: {@code subclassOf(className)}. */
    SUBCLASS_OF,
    /** Interface implementation: {@code implementing(interfaceName)}. */
    IMPLEMENTING,
    /** Logical AND of two expressions. */
    AND,
    /** Logical OR of two expressions. */
    OR,
    /** Logical NOT of an expression. */
    NOT
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
      PointcutExpression right) {
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
  }

  // ---- Factory methods ----

  /**
   * Creates an EXECUTION pointcut expression.
   *
   * @param returnType the return type pattern (e.g., {@code "*"}, {@code "void"})
   * @param classPattern the class name pattern (e.g., {@code "com.example..Service"})
   * @param methodPattern the method name pattern (e.g., {@code "process"})
   * @param paramPattern the parameter pattern (e.g., {@code ".."}, {@code "String,int"})
   * @return a new EXECUTION expression
   */
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
        null);
  }

  /**
   * Creates an AT_ANNOTATION pointcut expression.
   *
   * @param annotationClassName the fully-qualified annotation class name
   * @return a new AT_ANNOTATION expression
   */
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
        null);
  }

  /**
   * Creates an AT_WITHIN pointcut expression.
   *
   * @param annotationClassName the fully-qualified annotation class name
   * @return a new AT_WITHIN expression
   */
  public static PointcutExpression atWithin(String annotationClassName) {
    return new PointcutExpression(
        Type.AT_WITHIN, null, null, null, null, annotationClassName, null, null, null, null, null);
  }

  /**
   * Creates a WITHIN pointcut expression.
   *
   * @param packageName the package/class pattern (e.g., {@code "com.example.."})
   * @return a new WITHIN expression
   */
  public static PointcutExpression within(String packageName) {
    return new PointcutExpression(
        Type.WITHIN, null, null, null, null, null, packageName, null, null, null, null);
  }

  /**
   * Creates a SUBCLASS_OF pointcut expression.
   *
   * @param className the fully-qualified superclass name
   * @return a new SUBCLASS_OF expression
   */
  public static PointcutExpression subclassOf(String className) {
    return new PointcutExpression(
        Type.SUBCLASS_OF, null, null, null, null, null, null, className, null, null, null);
  }

  /**
   * Creates an IMPLEMENTING pointcut expression.
   *
   * @param interfaceName the fully-qualified interface name
   * @return a new IMPLEMENTING expression
   */
  public static PointcutExpression implementing(String interfaceName) {
    return new PointcutExpression(
        Type.IMPLEMENTING, null, null, null, null, null, null, null, interfaceName, null, null);
  }

  /**
   * Creates a composite AND expression.
   *
   * @param left the left operand
   * @param right the right operand
   * @return a new AND expression
   */
  public static PointcutExpression and(PointcutExpression left, PointcutExpression right) {
    return new PointcutExpression(
        Type.AND, null, null, null, null, null, null, null, null, left, right);
  }

  /**
   * Creates a composite OR expression.
   *
   * @param left the left operand
   * @param right the right operand
   * @return a new OR expression
   */
  public static PointcutExpression or(PointcutExpression left, PointcutExpression right) {
    return new PointcutExpression(
        Type.OR, null, null, null, null, null, null, null, null, left, right);
  }

  /**
   * Creates a NOT expression that negates the given expression.
   *
   * @param expr the expression to negate
   * @return a new NOT expression
   */
  public static PointcutExpression not(PointcutExpression expr) {
    return new PointcutExpression(
        Type.NOT, null, null, null, null, null, null, null, null, expr, null);
  }

  // ---- Getters ----

  /**
   * Returns the type of this expression.
   *
   * @return the expression type
   */
  public Type getType() {
    return type;
  }

  /**
   * Returns the return type pattern (EXECUTION only).
   *
   * @return the return type pattern, or null
   */
  public String getReturnType() {
    return returnType;
  }

  /**
   * Returns the class pattern (EXECUTION only).
   *
   * @return the class pattern, or null
   */
  public String getClassPattern() {
    return classPattern;
  }

  /**
   * Returns the method pattern (EXECUTION only).
   *
   * @return the method pattern, or null
   */
  public String getMethodPattern() {
    return methodPattern;
  }

  /**
   * Returns the parameter pattern (EXECUTION only).
   *
   * @return the parameter pattern, or null
   */
  public String getParamPattern() {
    return paramPattern;
  }

  /**
   * Returns the annotation class name (AT_ANNOTATION, AT_WITHIN only).
   *
   * @return the annotation class name, or null
   */
  public String getAnnotationClassName() {
    return annotationClassName;
  }

  /**
   * Returns the package pattern (WITHIN only).
   *
   * @return the package pattern, or null
   */
  public String getPackageName() {
    return packageName;
  }

  /**
   * Returns the class name (SUBCLASS_OF only).
   *
   * @return the class name, or null
   */
  public String getClassName() {
    return className;
  }

  /**
   * Returns the interface name (IMPLEMENTING only).
   *
   * @return the interface name, or null
   */
  public String getInterfaceName() {
    return interfaceName;
  }

  /**
   * Returns the left operand (AND, OR only).
   *
   * @return the left expression, or null
   */
  public PointcutExpression getLeft() {
    return left;
  }

  /**
   * Returns the right operand (AND, OR only).
   *
   * @return the right expression, or null
   */
  public PointcutExpression getRight() {
    return right;
  }

  /**
   * Returns the operand for NOT expression.
   *
   * @return the negated expression, or null
   */
  public PointcutExpression getOperand() {
    return left;
  }

  // ---- Conversion ----

  /**
   * Converts this expression into a {@link Pointcut}.
   *
   * <p>For composite expressions (AND/OR), both sides are converted and composed. For
   * AT_ANNOTATION, which only provides a MethodMatcher, this method uses {@link
   * MethodMatcher#any()} as the class matcher. Use {@link #toPointcutWithClassScope(ClassMatcher)}
   * if you need to supply a class scope from a companion expression.
   *
   * @return a new Pointcut representing this expression
   */
  public Pointcut toPointcut() {
    switch (type) {
      case EXECUTION:
        return new Pointcut(buildClassMatcher(), buildMethodMatcher());
      case AT_ANNOTATION:
        return new Pointcut(
            ClassMatcher.byNamePattern(".*"), MethodMatcher.byAnnotation(annotationClassName));
      case AT_WITHIN:
        return new Pointcut(ClassMatcher.byAnnotation(annotationClassName), MethodMatcher.any());
      case WITHIN:
        return new Pointcut(
            ClassMatcher.byNamePattern(convertToRegex(packageName)), MethodMatcher.any());
      case SUBCLASS_OF:
        return new Pointcut(ClassMatcher.bySuperClass(className), MethodMatcher.any());
      case IMPLEMENTING:
        return new Pointcut(ClassMatcher.byInterface(interfaceName), MethodMatcher.any());
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

  /**
   * Converts this expression into a Pointcut, using the given ClassMatcher as the class scope for
   * AT_ANNOTATION expressions.
   *
   * <p>This is useful when an AT_ANNOTATION expression is combined with an EXECUTION expression via
   * AND: the EXECUTION provides the class scope and the AT_ANNOTATION provides the method-level
   * annotation filter.
   *
   * @param classScope the class matcher to use for AT_ANNOTATION expressions
   * @return a new Pointcut representing this expression
   */
  public Pointcut toPointcutWithClassScope(ClassMatcher classScope) {
    switch (type) {
      case EXECUTION:
        return new Pointcut(buildClassMatcher(), buildMethodMatcher());
      case AT_ANNOTATION:
        return new Pointcut(classScope, MethodMatcher.byAnnotation(annotationClassName));
      case AT_WITHIN:
        return new Pointcut(ClassMatcher.byAnnotation(annotationClassName), MethodMatcher.any());
      case WITHIN:
        return new Pointcut(
            ClassMatcher.byNamePattern(convertToRegex(packageName)), MethodMatcher.any());
      case SUBCLASS_OF:
        return new Pointcut(ClassMatcher.bySuperClass(className), MethodMatcher.any());
      case IMPLEMENTING:
        return new Pointcut(ClassMatcher.byInterface(interfaceName), MethodMatcher.any());
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

  // ---- Internal helpers ----

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

  /**
   * Converts a pointcut wildcard pattern to a Java regex.
   *
   * <p>{@code ..} becomes {@code .*} and {@code *} becomes {@code [^.]*}.
   *
   * @param pattern the wildcard pattern
   * @return the equivalent Java regex
   */
  static String convertToRegex(String pattern) {
    if (pattern == null || pattern.isEmpty()) {
      return ".*";
    }
    // Escape regex metacharacters first, except * and .
    String regex = pattern;
    // Escape special regex chars: \, [, ], (, ), {, }, +, ?, ^, $, |
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
    // Now handle pointcut wildcards
    // .. -> .*  (must do before single * replacement)
    regex = regex.replace("..", "PLACEHOLDER_DOUBLE_DOT");
    // * -> [^.]*  (single segment wildcard)
    regex = regex.replace("*", "[^.]*");
    // Restore ..
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
