package com.github.cc11001100.weavergirl.core.registry;

import java.util.Arrays;
import java.util.List;

/**
 * The classes on the current call stack, shallowest first, as the class objects that actually ran.
 *
 * <p>This is the Java 8 path. {@code StackWalker} does not exist there, and a stack trace only carries the class
 * name, so two plugins that each define a class of the same name cannot be told apart from one. {@code
 * getClassContext()} hands over the class object itself, and reading your own class context needs no permission.
 * A security manager that is already installed never sees the call.
 *
 * <p>On Java 9 and newer {@link CallStack} is used instead, because {@code SecurityManager} is deprecated there
 * and slated for removal. This class stays as the fallback for a runtime that cannot load {@code CallStack}.
 */
@SuppressWarnings("removal")
final class ClassContext extends SecurityManager {

  private ClassContext() {}

  static List<Class<?>> classes() {
    return Arrays.asList(new ClassContext().getClassContext());
  }
}
