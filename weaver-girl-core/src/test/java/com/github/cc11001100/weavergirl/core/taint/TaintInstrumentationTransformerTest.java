package com.github.cc11001100.weavergirl.core.taint;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import net.bytebuddy.agent.builder.AgentBuilder;
import org.junit.jupiter.api.Test;

/**
 * Tests for TaintInstrumentation named Transformer classes.
 *
 * <p>These tests verify that the anonymous transformers were converted to named static inner
 * classes, which are now properly testable and covered.
 */
class TaintInstrumentationTransformerTest {

  @Test
  void stringTransformer_isPublicStaticInnerClass() {
    Class<?> transformerClass = TaintInstrumentation.StringTransformer.class;
    assertTrue(Modifier.isPublic(transformerClass.getModifiers()));
    assertTrue(Modifier.isStatic(transformerClass.getModifiers()));
    assertTrue(transformerClass.getName().contains("$StringTransformer"));
  }

  @Test
  void stringBuilderTransformer_isPublicStaticInnerClass() {
    Class<?> transformerClass = TaintInstrumentation.StringBuilderTransformer.class;
    assertTrue(Modifier.isPublic(transformerClass.getModifiers()));
    assertTrue(Modifier.isStatic(transformerClass.getModifiers()));
    assertTrue(transformerClass.getName().contains("$StringBuilderTransformer"));
  }

  @Test
  void processBuilderTransformer_isPublicStaticInnerClass() {
    Class<?> transformerClass = TaintInstrumentation.ProcessBuilderTransformer.class;
    assertTrue(Modifier.isPublic(transformerClass.getModifiers()));
    assertTrue(Modifier.isStatic(transformerClass.getModifiers()));
    assertTrue(transformerClass.getName().contains("$ProcessBuilderTransformer"));
  }

  @Test
  void runtimeTransformer_isPublicStaticInnerClass() {
    Class<?> transformerClass = TaintInstrumentation.RuntimeTransformer.class;
    assertTrue(Modifier.isPublic(transformerClass.getModifiers()));
    assertTrue(Modifier.isStatic(transformerClass.getModifiers()));
    assertTrue(transformerClass.getName().contains("$RuntimeTransformer"));
  }

  @Test
  void allTransformers_implementAgentBuilderTransformer() {
    assertTrue(
        AgentBuilder.Transformer.class.isAssignableFrom(
            TaintInstrumentation.StringTransformer.class));
    assertTrue(
        AgentBuilder.Transformer.class.isAssignableFrom(
            TaintInstrumentation.StringBuilderTransformer.class));
    assertTrue(
        AgentBuilder.Transformer.class.isAssignableFrom(
            TaintInstrumentation.ProcessBuilderTransformer.class));
    assertTrue(
        AgentBuilder.Transformer.class.isAssignableFrom(TaintInstrumentation.RuntimeTransformer.class));
  }
}
