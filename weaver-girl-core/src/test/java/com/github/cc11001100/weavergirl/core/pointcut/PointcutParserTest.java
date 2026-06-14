package com.github.cc11001100.weavergirl.core.pointcut;

import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PointcutParser} and the {@link PointcutExpression} DSL.
 *
 * @since 1.1.0
 */
class PointcutParserTest {

    private final PointcutParser parser = PointcutParser.getInstance();

    // ---- Parsing tests ----

    @Test
    void parse_execution_exactClassAndMethod() {
        PointcutExpression expr = parser.parse("execution(void com.example.Service.process(String))");

        assertEquals(PointcutExpression.Type.EXECUTION, expr.getType());
        assertEquals("void", expr.getReturnType());
        assertEquals("com.example.Service", expr.getClassPattern());
        assertEquals("process", expr.getMethodPattern());
        assertEquals("String", expr.getParamPattern());
    }

    @Test
    void parse_execution_patternClass() {
        PointcutExpression expr = parser.parse("execution(* com.example..Service.process(..))");

        assertEquals(PointcutExpression.Type.EXECUTION, expr.getType());
        assertEquals("*", expr.getReturnType());
        assertEquals("com.example..Service", expr.getClassPattern());
        assertEquals("process", expr.getMethodPattern());
        assertEquals("..", expr.getParamPattern());
    }

    @Test
    void parse_atAnnotation() {
        PointcutExpression expr = parser.parse("@annotation(com.example.Trace)");

        assertEquals(PointcutExpression.Type.AT_ANNOTATION, expr.getType());
        assertEquals("com.example.Trace", expr.getAnnotationClassName());
    }

    @Test
    void parse_atWithin() {
        PointcutExpression expr = parser.parse("@within(com.example.Service)");

        assertEquals(PointcutExpression.Type.AT_WITHIN, expr.getType());
        assertEquals("com.example.Service", expr.getAnnotationClassName());
    }

    @Test
    void parse_subclassOf() {
        PointcutExpression expr = parser.parse("subclassOf(com.example.BaseService)");

        assertEquals(PointcutExpression.Type.SUBCLASS_OF, expr.getType());
        assertEquals("com.example.BaseService", expr.getClassName());
    }

    @Test
    void parse_implementing() {
        PointcutExpression expr = parser.parse("implementing(java.io.Serializable)");

        assertEquals(PointcutExpression.Type.IMPLEMENTING, expr.getType());
        assertEquals("java.io.Serializable", expr.getInterfaceName());
    }

    @Test
    void parse_within() {
        PointcutExpression expr = parser.parse("within(com.example..)");

        assertEquals(PointcutExpression.Type.WITHIN, expr.getType());
        assertEquals("com.example..", expr.getPackageName());
    }

    @Test
    void parse_compositeAnd() {
        PointcutExpression expr = parser.parse(
                "execution(* com.example.Service.process(..)) && @annotation(com.example.Trace)");

        assertEquals(PointcutExpression.Type.AND, expr.getType());
        assertNotNull(expr.getLeft());
        assertNotNull(expr.getRight());
        assertEquals(PointcutExpression.Type.EXECUTION, expr.getLeft().getType());
        assertEquals(PointcutExpression.Type.AT_ANNOTATION, expr.getRight().getType());
    }

    @Test
    void parse_compositeOr() {
        PointcutExpression expr = parser.parse(
                "within(com.example..) || within(org.example..)");

        assertEquals(PointcutExpression.Type.OR, expr.getType());
        assertNotNull(expr.getLeft());
        assertNotNull(expr.getRight());
        assertEquals(PointcutExpression.Type.WITHIN, expr.getLeft().getType());
        assertEquals(PointcutExpression.Type.WITHIN, expr.getRight().getType());
    }

    // ---- toPointcut() tests ----

    @Test
    void toPointcut_exactClassAndMethod() {
        PointcutExpression expr = parser.parse("execution(void com.example.Service.process(String))");
        Pointcut pointcut = expr.toPointcut();

        assertEquals(ClassMatcher.MatchType.EXACT_NAME, pointcut.getClassMatcher().getMatchType());
        assertEquals("com.example.Service", pointcut.getClassMatcher().getPattern());
        assertEquals(MethodMatcher.MatchType.EXACT_NAME, pointcut.getMethodMatcher().getMatchType());
        assertEquals("process", pointcut.getMethodMatcher().getPattern());

        assertTrue(pointcut.matches("com.example.Service", "process"));
        assertFalse(pointcut.matches("com.example.Other", "process"));
        assertFalse(pointcut.matches("com.example.Service", "other"));
    }

    @Test
    void toPointcut_patternClass() {
        PointcutExpression expr = parser.parse("execution(* com.example..*.*(..))");
        Pointcut pointcut = expr.toPointcut();

        assertEquals(ClassMatcher.MatchType.NAME_PATTERN, pointcut.getClassMatcher().getMatchType());
        // "com.example.." -> "com\.example\..*" and "*" -> "[^.]*"
        assertTrue(pointcut.getClassMatcher().matches("com.example.Service"));
        assertTrue(pointcut.getClassMatcher().matches("com.example.sub.Service"));
        assertFalse(pointcut.getClassMatcher().matches("org.example.Service"));
    }

    @Test
    void toPointcut_atWithin() {
        PointcutExpression expr = parser.parse("@within(com.example.Service)");
        Pointcut pointcut = expr.toPointcut();

        assertEquals(ClassMatcher.MatchType.ANNOTATION, pointcut.getClassMatcher().getMatchType());
        assertEquals("com.example.Service", pointcut.getClassMatcher().getPattern());
        assertEquals(MethodMatcher.MatchType.ANY, pointcut.getMethodMatcher().getMatchType());
    }

    @Test
    void toPointcut_compositeAnd() {
        PointcutExpression expr = parser.parse(
                "execution(* com.example.Service.process(..)) && @annotation(com.example.Trace)");
        Pointcut pointcut = expr.toPointcut();

        // The composite pointcut should combine execution's class matcher with
        // @annotation's method matcher via AND
        assertNotNull(pointcut);
        // Verify the execution side provides class matching
        assertEquals("com.example.Service", pointcut.getClassMatcher().getPattern());
        assertEquals(ClassMatcher.MatchType.EXACT_NAME, pointcut.getClassMatcher().getMatchType());
    }

    // ---- Invalid input tests ----

    @Test
    void parse_invalidExpression_throws() {
        // Empty string
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                parser.parse("");
            }
        });

        // Whitespace only
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                parser.parse("   ");
            }
        });

        // Invalid keyword
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                parser.parse("invalid");
            }
        });

        // execution without parentheses
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                parser.parse("execution(missing-params");
            }
        });
    }
}
