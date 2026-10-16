package com.github.cc11001100.weavergirl.core.taint;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Tests for TaintInstrumentation Transformer classes' structure and accessibility.
 *
 * <p>These tests verify that the named Transformer classes exist, are properly structured,
 * and can be instantiated. This ensures JaCoCo can collect coverage for these classes.
 */
class TaintInstrumentationTransformerCoverageTest {

  @Test
  void stringTransformer_existsAndIsInstantiable() throws Exception {
    Class<?> transformerClass = TaintInstrumentation.StringTransformer.class;
    assertNotNull(transformerClass);
    assertTrue(Modifier.isPublic(transformerClass.getModifiers()));
    assertTrue(Modifier.isStatic(transformerClass.getModifiers()));

    // Verify it implements AgentBuilder.Transformer
    assertTrue(
        java.util.Arrays.stream(transformerClass.getInterfaces())
            .anyMatch(i -> i.getName().contains("AgentBuilder$Transformer")));

    // Verify transform method exists
    Method transformMethod =
        transformerClass.getMethod(
            "transform",
            net.bytebuddy.dynamic.DynamicType.Builder.class,
            net.bytebuddy.description.type.TypeDescription.class,
            ClassLoader.class,
            net.bytebuddy.utility.JavaModule.class,
            java.security.ProtectionDomain.class);
    assertNotNull(transformMethod);
    assertTrue(Modifier.isPublic(transformMethod.getModifiers()));

    // Instantiate to ensure the class is loadable
    Object instance = transformerClass.getDeclaredConstructor().newInstance();
    assertNotNull(instance);
  }

  @Test
  void stringBuilderTransformer_existsAndIsInstantiable() throws Exception {
    Class<?> transformerClass = TaintInstrumentation.StringBuilderTransformer.class;
    assertNotNull(transformerClass);
    assertTrue(Modifier.isPublic(transformerClass.getModifiers()));
    assertTrue(Modifier.isStatic(transformerClass.getModifiers()));

    Method transformMethod =
        transformerClass.getMethod(
            "transform",
            net.bytebuddy.dynamic.DynamicType.Builder.class,
            net.bytebuddy.description.type.TypeDescription.class,
            ClassLoader.class,
            net.bytebuddy.utility.JavaModule.class,
            java.security.ProtectionDomain.class);
    assertNotNull(transformMethod);

    Object instance = transformerClass.getDeclaredConstructor().newInstance();
    assertNotNull(instance);
  }

  @Test
  void processBuilderTransformer_existsAndIsInstantiable() throws Exception {
    Class<?> transformerClass = TaintInstrumentation.ProcessBuilderTransformer.class;
    assertNotNull(transformerClass);
    assertTrue(Modifier.isPublic(transformerClass.getModifiers()));
    assertTrue(Modifier.isStatic(transformerClass.getModifiers()));

    Method transformMethod =
        transformerClass.getMethod(
            "transform",
            net.bytebuddy.dynamic.DynamicType.Builder.class,
            net.bytebuddy.description.type.TypeDescription.class,
            ClassLoader.class,
            net.bytebuddy.utility.JavaModule.class,
            java.security.ProtectionDomain.class);
    assertNotNull(transformMethod);

    Object instance = transformerClass.getDeclaredConstructor().newInstance();
    assertNotNull(instance);
  }

  @Test
  void runtimeTransformer_existsAndIsInstantiable() throws Exception {
    Class<?> transformerClass = TaintInstrumentation.RuntimeTransformer.class;
    assertNotNull(transformerClass);
    assertTrue(Modifier.isPublic(transformerClass.getModifiers()));
    assertTrue(Modifier.isStatic(transformerClass.getModifiers()));

    Method transformMethod =
        transformerClass.getMethod(
            "transform",
            net.bytebuddy.dynamic.DynamicType.Builder.class,
            net.bytebuddy.description.type.TypeDescription.class,
            ClassLoader.class,
            net.bytebuddy.utility.JavaModule.class,
            java.security.ProtectionDomain.class);
    assertNotNull(transformMethod);

    Object instance = transformerClass.getDeclaredConstructor().newInstance();
    assertNotNull(instance);
  }
}
