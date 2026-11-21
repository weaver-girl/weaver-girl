package com.github.cc11001100.weavergirl.core.scanner;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.Before;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * The scan reads the class file before loading it, for two reasons: a target class loaded early is
 * past the transformer and never woven, and under the agent the annotation type exists twice. Both
 * make an in-memory byte check the only reliable one, so it is tested on its own.
 */
class WeaveClassByteScanTest {

  @WeaveClass(target = "com.example.bytescan.Target")
  static class Annotated {
    @Before("run")
    public void before(MethodInvocation inv) {}
  }

  static class Plain {
    public void run() {}
  }

  private static byte[] bytesOf(Class<?> type) throws Exception {
    String resource = type.getName().replace('.', '/') + ".class";
    try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  @Test
  void annotatedClassFileIsRecognisedWithoutLoadingTheClass() throws Exception {
    assertTrue(AnnotatedClassScanner.hasWeaveClassInBytes(bytesOf(Annotated.class)));
  }

  @Test
  void plainClassFileIsNotMistakenForAnnotated() throws Exception {
    assertFalse(AnnotatedClassScanner.hasWeaveClassInBytes(bytesOf(Plain.class)));
  }

  @Test
  void garbageBytesAreNotAnnotated() {
    assertFalse(AnnotatedClassScanner.hasWeaveClassInBytes(new byte[] {1, 2, 3, 4, 5}));
    assertFalse(AnnotatedClassScanner.hasWeaveClassInBytes(new byte[0]));
  }

  /** A {@code pointcut} value must not be read as a stray attribute and confuse the walk. */
  @Test
  void annotatedClassWithExtraAttributeValuesIsStillRecognised() throws Exception {
    assertTrue(
        AnnotatedClassScanner.hasWeaveClassInBytes(bytesOf(ExtraValues.class)));
  }

  @WeaveClass(target = "com.example.bytescan.Target", pointcut = "execution(* *(..))")
  static class ExtraValues {
    @Before(value = "run", parameterTypes = {"java.lang.String"})
    public void before(MethodInvocation inv) {}
  }

  @Test
  void descriptorIsTurnedIntoAClassName() {
    assertEquals(
        "com.github.cc11001100.weavergirl.annotation.WeaveClass",
        AnnotatedClassScanner.descriptorToClassName(
            "Lcom/github/cc11001100/weavergirl/annotation/WeaveClass;"));
    // Not a class descriptor: left alone rather than mangled.
    assertEquals("java/lang/String", AnnotatedClassScanner.descriptorToClassName("java/lang/String"));
  }
}
