package com.github.cc11001100.weavergirl.core.registry;

import java.lang.StackWalker.StackFrame;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The classes on the current call stack, shallowest first, as the class objects that actually ran.
 *
 * <p>Compiled for Java 9 and shipped under {@code META-INF/versions/9}, so a Java 8 runtime never loads it and
 * keeps the name-based fallback. A stack trace only carries the class name, and two plugins can each define a
 * class of the same name; the class object is what tells them apart. {@code StackWalker} is the one API that
 * hands the class object over, and its frame type lives in a package nothing outside {@code java.base} may
 * reflect into, so the call has to be compiled directly against it.
 */
final class CallStack {

  private CallStack() {}

  static List<Class<?>> classes() {
    return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
        .walk(stream -> stream.map(StackFrame::getDeclaringClass).collect(Collectors.toList()));
  }
}
