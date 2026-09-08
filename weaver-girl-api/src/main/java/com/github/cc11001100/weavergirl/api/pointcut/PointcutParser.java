package com.github.cc11001100.weavergirl.api.pointcut;

import com.github.cc11001100.weavergirl.api.ValidationUtils;

/**
 * Parses pointcut expression strings into {@link PointcutExpression} instances.
 *
 * <h3>Supported expression forms</h3>
 *
 * <table>
 *   <tr><th>Syntax</th><th>Type</th><th>Example</th></tr>
 *   <tr><td>{@code execution(retType class.method(params))}</td><td>{@link PointcutExpression.Type#EXECUTION}</td>
 *       <td>{@code execution(* com.example.Service.process(..))}</td></tr>
 *   <tr><td>{@code @annotation(className)}</td><td>{@link PointcutExpression.Type#AT_ANNOTATION}</td>
 *       <td>{@code @annotation(com.example.Trace)}</td></tr>
 *   <tr><td>{@code @within(className)}</td><td>{@link PointcutExpression.Type#AT_WITHIN}</td>
 *       <td>{@code @within(com.example.Service)}</td></tr>
 *   <tr><td>{@code within(packagePattern)}</td><td>{@link PointcutExpression.Type#WITHIN}</td>
 *       <td>{@code within(com.example..)}</td></tr>
 *   <tr><td>{@code subclassOf(className)}</td><td>{@link PointcutExpression.Type#SUBCLASS_OF}</td>
 *       <td>{@code subclassOf(com.example.BaseService)}</td></tr>
 *   <tr><td>{@code implementing(interfaceName)}</td><td>{@link PointcutExpression.Type#IMPLEMENTING}</td>
 *       <td>{@code implementing(java.io.Serializable)}</td></tr>
 *   <tr><td>{@code expr1 && expr2}</td><td>{@link PointcutExpression.Type#AND}</td>
 *       <td>{@code execution(* com.example..*(..)) && @annotation(com.example.Trace)}</td></tr>
 *   <tr><td>{@code expr1 || expr2}</td><td>{@link PointcutExpression.Type#OR}</td>
 *       <td>{@code within(com.example..) || within(org.example..)}</td></tr>
 * </table>
 *
 * <p>Parentheses may be used for grouping in composite expressions.
 *
 * @see PointcutExpression
 * @since 1.1.0
 */
public class PointcutParser {

  /** The shared singleton parser instance. */
  private static final PointcutParser INSTANCE = new PointcutParser();

  private PointcutParser() {
    // singleton
  }

  /**
   * Returns the shared parser instance.
   *
   * @return the singleton parser
   */
  public static PointcutParser getInstance() {
    return INSTANCE;
  }

  /**
   * Parses a pointcut expression string into a {@link PointcutExpression}.
   *
   * @param expression the expression string (e.g., {@code "execution(* com.example..*(..))"})
   * @return the parsed PointcutExpression
   * @throws IllegalArgumentException if the expression is null, empty, or malformed
   */
  public PointcutExpression parse(String expression) {
    ValidationUtils.requireNonEmpty(expression, "expression");
    return parseInternal(expression.trim());
  }

  private PointcutExpression parseInternal(String expr) {
    // Handle outer parentheses: (expr)
    expr = expr.trim();
    if (expr.startsWith("(") && findMatchingParen(expr, 0) == expr.length() - 1) {
      return parseInternal(expr.substring(1, expr.length() - 1).trim());
    }

    // Try to split by || (OR has lowest precedence)
    int orIndex = findOperatorIndex(expr, "||");
    if (orIndex >= 0) {
      PointcutExpression left = parseInternal(expr.substring(0, orIndex).trim());
      PointcutExpression right = parseInternal(expr.substring(orIndex + 2).trim());
      return PointcutExpression.or(left, right);
    }

    // Try to split by && (AND has higher precedence than OR)
    int andIndex = findOperatorIndex(expr, "&&");
    if (andIndex >= 0) {
      PointcutExpression left = parseInternal(expr.substring(0, andIndex).trim());
      PointcutExpression right = parseInternal(expr.substring(andIndex + 2).trim());
      return PointcutExpression.and(left, right);
    }

    // Handle NOT operator: !expr — highest precedence among logical operators
    if (expr.startsWith("!")) {
      String rest = expr.substring(1).trim();
      if (rest.isEmpty()) {
        throw new IllegalArgumentException("Invalid pointcut expression: dangling '!' operator");
      }
      PointcutExpression negated = parseInternal(rest);
      return PointcutExpression.not(negated);
    }

    // Parse atomic expression
    return parseAtomic(expr);
  }

  /**
   * Finds the index of a binary operator (&& or ||) at the top level (not inside parentheses).
   *
   * @param expr the expression string
   * @param operator the operator to find ("&&" or "||")
   * @return the index of the operator, or -1 if not found at top level
   */
  private int findOperatorIndex(String expr, String operator) {
    int depth = 0;
    for (int i = 0; i < expr.length(); i++) {
      char c = expr.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (depth == 0
          && i + operator.length() <= expr.length()
          && expr.substring(i, i + operator.length()).equals(operator)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Finds the closing parenthesis matching the one at the given index.
   *
   * @param expr the expression string
   * @param start the index of the opening parenthesis
   * @return the index of the closing parenthesis, or -1 if not found
   */
  private int findMatchingParen(String expr, int start) {
    int depth = 0;
    for (int i = start; i < expr.length(); i++) {
      if (expr.charAt(i) == '(') {
        depth++;
      } else if (expr.charAt(i) == ')') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  private PointcutExpression parseAtomic(String expr) {
    if (expr.startsWith("execution(") && expr.endsWith(")")) {
      return parseExecution(expr.substring("execution(".length(), expr.length() - 1));
    }
    if (expr.startsWith("@annotation(") && expr.endsWith(")")) {
      String content = expr.substring("@annotation(".length(), expr.length() - 1).trim();
      return PointcutExpression.atAnnotation(content);
    }
    if (expr.startsWith("@within(") && expr.endsWith(")")) {
      String content = expr.substring("@within(".length(), expr.length() - 1).trim();
      return PointcutExpression.atWithin(content);
    }
    if (expr.startsWith("within(") && expr.endsWith(")")) {
      String content = expr.substring("within(".length(), expr.length() - 1).trim();
      return PointcutExpression.within(content);
    }
    if (expr.startsWith("subclassOf(") && expr.endsWith(")")) {
      String content = expr.substring("subclassOf(".length(), expr.length() - 1).trim();
      return PointcutExpression.subclassOf(content);
    }
    if (expr.startsWith("implementing(") && expr.endsWith(")")) {
      String content = expr.substring("implementing(".length(), expr.length() - 1).trim();
      return PointcutExpression.implementing(content);
    }
    throw new IllegalArgumentException("Invalid pointcut expression: " + expr);
  }

  /**
   * Parses the content inside {@code execution(...)}.
   *
   * <p>Expected format: {@code returnType classPattern.methodPattern(paramPattern)}
   *
   * @param content the inner content (e.g., {@code "* com.example.Service.process(..)"})
   * @return an EXECUTION PointcutExpression
   */
  private PointcutExpression parseExecution(String content) {
    content = content.trim();

    // Find the last '(' that starts the parameter list
    int paramStart = content.lastIndexOf('(');
    int paramEnd = content.lastIndexOf(')');
    if (paramStart < 0 || paramEnd < 0 || paramEnd <= paramStart) {
      throw new IllegalArgumentException(
          "Invalid execution expression — missing parameter parentheses: execution("
              + content
              + ")");
    }

    String paramPattern = content.substring(paramStart + 1, paramEnd).trim();
    String beforeParams = content.substring(0, paramStart).trim();

    // Split returnType from classPattern.methodPattern
    // The first space separates return type from the qualified method pattern
    int spaceIdx = beforeParams.indexOf(' ');
    if (spaceIdx < 0) {
      throw new IllegalArgumentException(
          "Invalid execution expression — expected 'returnType class.method(params)': execution("
              + content
              + ")");
    }

    String returnType = beforeParams.substring(0, spaceIdx).trim();
    String qualifiedMethod = beforeParams.substring(spaceIdx + 1).trim();

    // Split classPattern from methodPattern at the last '.'
    int lastDot = qualifiedMethod.lastIndexOf('.');
    if (lastDot < 0) {
      throw new IllegalArgumentException(
          "Invalid execution expression — expected 'class.method': execution(" + content + ")");
    }

    String classPattern = qualifiedMethod.substring(0, lastDot);
    String methodPattern = qualifiedMethod.substring(lastDot + 1);

    return PointcutExpression.execution(returnType, classPattern, methodPattern, paramPattern);
  }
}
