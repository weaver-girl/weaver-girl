package com.github.cc11001100.weavergirl.core.transformer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;

/**
 * {@link TypeExistenceChecker} decides from classes already loaded and from class files visible as
 * resources, and it only reflects over a method when the class is already loaded.
 */
class TypeExistenceCheckerTest {

  private final Instrumentation instrumentation = ByteBuddyAgent.install();

  /** A class the JVM has already loaded exists, and one that names nothing does not. */
  @Test
  void aLoadedClassExistsAndAnUnknownOneDoesNot() {
    TypeExistenceChecker checker = new TypeExistenceChecker(instrumentation);

    assertTrue(checker.exists(String.class.getName()));
    assertFalse(checker.exists("com.example.DoesNotExist"));
  }

  /** A class that is not loaded yet still exists when its class file is visible as a resource. */
  @Test
  void aClassVisibleOnlyAsAResourceExists() {
    TypeExistenceChecker checker = new TypeExistenceChecker(instrumentation);

    // This test class is on the classpath but deliberately not loaded, so only the resource probe
    // can see it.
    assertTrue(checker.exists("com.github.cc11001100.weavergirl.core.transformer.UnloadedProbe"));
  }

  /** A method is found only on a class that is already loaded. */
  @Test
  void aMethodIsFoundOnlyWhenItsClassIsLoaded() {
    TypeExistenceChecker checker = new TypeExistenceChecker(instrumentation);

    assertTrue(checker.methodExists(String.class.getName(), "length"));
    assertFalse(checker.methodExists(String.class.getName(), "noSuchMethod"));
    assertFalse(checker.methodExists("com.example.DoesNotExist", "length"));
    // Present as a resource but never loaded, so its methods cannot be reflected over.
    assertFalse(
        checker.methodExists(
            "com.github.cc11001100.weavergirl.core.transformer.UnloadedProbe", "probe"));
  }
}
