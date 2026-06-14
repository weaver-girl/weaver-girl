package com.github.cc11001100.weavergirl.core.pointcut;

import com.github.cc11001100.weavergirl.api.pointcut.PointcutExpression;
import com.github.cc11001100.weavergirl.api.pointcut.PointcutParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PointcutNotOperatorTest {

    @Test
    void parse_notAnnotation() {
        PointcutExpression expr = PointcutParser.getInstance().parse("!@annotation(com.example.NoTrace)");
        assertEquals(PointcutExpression.Type.NOT, expr.getType());
        assertEquals(PointcutExpression.Type.AT_ANNOTATION, expr.getOperand().getType());
        assertEquals("com.example.NoTrace", expr.getOperand().getAnnotationClassName());
    }

    @Test
    void parse_notWithin() {
        PointcutExpression expr = PointcutParser.getInstance().parse("!within(com.example.internal..)");
        assertEquals(PointcutExpression.Type.NOT, expr.getType());
        assertEquals(PointcutExpression.Type.WITHIN, expr.getOperand().getType());
    }

    @Test
    void parse_andWithNot() {
        PointcutExpression expr = PointcutParser.getInstance().parse(
                "execution(* com.example..*(..)) && !@annotation(com.example.NoTrace)");
        assertEquals(PointcutExpression.Type.AND, expr.getType());
        assertEquals(PointcutExpression.Type.EXECUTION, expr.getLeft().getType());
        assertEquals(PointcutExpression.Type.NOT, expr.getRight().getType());
    }

    @Test
    void parse_notWithParentheses() {
        PointcutExpression expr = PointcutParser.getInstance().parse(
                "!(within(com.example..) || within(org.example..))");
        assertEquals(PointcutExpression.Type.NOT, expr.getType());
        assertEquals(PointcutExpression.Type.OR, expr.getOperand().getType());
    }

    @Test
    void toString_notAnnotation() {
        PointcutExpression expr = PointcutParser.getInstance().parse("!@annotation(com.example.NoTrace)");
        assertEquals("!(@annotation(com.example.NoTrace))", expr.toString());
    }

    @Test
    void parse_invalidNot_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                PointcutParser.getInstance().parse("!"));
    }
}
