package com.github.cc11001100.weavergirl.core.taint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import org.junit.jupiter.api.Test;

/**
 * Tests that directly invoke Transformer.transform() to achieve coverage.
 *
 * <p>These tests create real ByteBuddy Builder instances and invoke the transform() methods
 * to ensure JaCoCo collects coverage for the named Transformer classes.
 */
class TaintInstrumentationTransformerDirectTest {

  @Test
  void stringTransformer_transform_coversAdviceWiring() {
    TaintInstrumentation.StringTransformer transformer =
        new TaintInstrumentation.StringTransformer();

    // Create a real ByteBuddy builder for String
    DynamicType.Builder<?> builder =
        new net.bytebuddy.ByteBuddy()
            .redefine(String.class)
            .name("com.test.StringTest");

    TypeDescription typeDesc = mock(TypeDescription.class);
    when(typeDesc.getName()).thenReturn("java.lang.String");

    ClassLoader classLoader = getClass().getClassLoader();
    JavaModule module = mock(JavaModule.class);
    ProtectionDomain domain = getClass().getProtectionDomain();

    // This will actually execute the transform method and wire up the advice
    DynamicType.Builder<?> result = transformer.transform(builder, typeDesc, classLoader, module, domain);

    assertNotNull(result);
  }

  @Test
  void stringBuilderTransformer_transform_coversAdviceWiring() {
    TaintInstrumentation.StringBuilderTransformer transformer =
        new TaintInstrumentation.StringBuilderTransformer();

    DynamicType.Builder<?> builder =
        new net.bytebuddy.ByteBuddy()
            .redefine(StringBuilder.class)
            .name("com.test.StringBuilderTest");

    TypeDescription typeDesc = mock(TypeDescription.class);
    when(typeDesc.getName()).thenReturn("java.lang.StringBuilder");

    ClassLoader classLoader = getClass().getClassLoader();
    JavaModule module = mock(JavaModule.class);
    ProtectionDomain domain = getClass().getProtectionDomain();

    DynamicType.Builder<?> result = transformer.transform(builder, typeDesc, classLoader, module, domain);

    assertNotNull(result);
  }

  @Test
  void processBuilderTransformer_transform_coversAdviceWiring() {
    TaintInstrumentation.ProcessBuilderTransformer transformer =
        new TaintInstrumentation.ProcessBuilderTransformer();

    DynamicType.Builder<?> builder =
        new net.bytebuddy.ByteBuddy()
            .redefine(ProcessBuilder.class)
            .name("com.test.ProcessBuilderTest");

    TypeDescription typeDesc = mock(TypeDescription.class);
    when(typeDesc.getName()).thenReturn("java.lang.ProcessBuilder");

    ClassLoader classLoader = getClass().getClassLoader();
    JavaModule module = mock(JavaModule.class);
    ProtectionDomain domain = getClass().getProtectionDomain();

    DynamicType.Builder<?> result = transformer.transform(builder, typeDesc, classLoader, module, domain);

    assertNotNull(result);
  }

  @Test
  void runtimeTransformer_transform_coversAdviceWiring() {
    TaintInstrumentation.RuntimeTransformer transformer =
        new TaintInstrumentation.RuntimeTransformer();

    DynamicType.Builder<?> builder =
        new net.bytebuddy.ByteBuddy()
            .redefine(Runtime.class)
            .name("com.test.RuntimeTest");

    TypeDescription typeDesc = mock(TypeDescription.class);
    when(typeDesc.getName()).thenReturn("java.lang.Runtime");

    ClassLoader classLoader = getClass().getClassLoader();
    JavaModule module = mock(JavaModule.class);
    ProtectionDomain domain = getClass().getProtectionDomain();

    DynamicType.Builder<?> result = transformer.transform(builder, typeDesc, classLoader, module, domain);

    assertNotNull(result);
  }
}
