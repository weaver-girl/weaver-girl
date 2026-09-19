package com.github.cc11001100.weavergirl.core.scanner;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.Before;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.plugin.TestInterceptorRegistry;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnnotatedClassScannerTest {

  // Test interceptor class that should be discovered by scanning
  @WeaveClass(target = "com.example.test.TargetService")
  public static class DiscoverableInterceptor {
    @Before("process")
    public void beforeProcess(MethodInvocation inv) {}
  }

  // Non-interceptor class that should NOT be discovered
  public static class NotAnInterceptor {
    public void someMethod() {}
  }

  @Test
  void scan_shouldFindWeaveClassAnnotatedClasses() {
    AnnotatedClassScanner scanner = new AnnotatedClassScanner();
    // Scan the package where this test class lives
    Set<Class<?>> found = scanner.scan("com.github.cc11001100.weavergirl.core.scanner");

    // Should find DiscoverableInterceptor
    assertTrue(
        found.contains(DiscoverableInterceptor.class), "Should find @WeaveClass-annotated class");
    // Should NOT find NotAnInterceptor
    assertFalse(found.contains(NotAnInterceptor.class), "Should not find non-annotated class");
  }

  @Test
  void scanAndLoad_shouldRegisterFoundInterceptors() {
    InterceptorRegistry registry = new TestInterceptorRegistry();
    AnnotatedClassScanner scanner = new AnnotatedClassScanner();

    int count = scanner.scanAndLoad(registry, "com.github.cc11001100.weavergirl.core.scanner");

    assertTrue(count >= 1, "Should find at least one annotated class");
    assertFalse(
        registry.getAllDefinitions().isEmpty(),
        "Registry should contain interceptor definitions from scanned classes");
  }

  @Test
  void scan_emptyPackage_shouldReturnEmptySet() {
    AnnotatedClassScanner scanner = new AnnotatedClassScanner();
    Set<Class<?>> found = scanner.scan("com.nonexistent.package.xyz");
    assertTrue(found.isEmpty(), "Non-existent package should return empty set");
  }
}
