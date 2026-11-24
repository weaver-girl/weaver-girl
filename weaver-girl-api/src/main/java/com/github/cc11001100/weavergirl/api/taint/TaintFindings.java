package com.github.cc11001100.weavergirl.api.taint;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes taint findings on the public {@link InterceptorEventPublisher} path (listeners and
 * active exporters). Subscribers see {@code source}, {@code sink}, {@code argument}, the half-open
 * {@code start}/{@code end} of that slice inside the argument, and the application frame that
 * reached the sink ({@code callerClass}, {@code callerMethod}, {@code callerLine}).
 *
 * @since 1.0.0
 */
public final class TaintFindings {

  public static final String EVENT_TYPE = "iast-finding";

  /** Frames to skip when locating the sink call site: the taint API's own bookkeeping. */
  private static final String[] INTERNAL_CLASSES = {
    "com.github.cc11001100.weavergirl.api.taint.TaintBridge",
    "com.github.cc11001100.weavergirl.api.taint.TaintPropagation",
    "com.github.cc11001100.weavergirl.api.taint.TaintConcat",
    "com.github.cc11001100.weavergirl.api.taint.TaintFindings",
    "com.github.cc11001100.weavergirl.api.taint.CommandExecutionSink",
    "com.github.cc11001100.weavergirl.api.taint.SqlExecutionSink",
    "com.github.cc11001100.weavergirl.api.taint.Taint"
  };

  private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

  private TaintFindings() {}

  public static void install() {
    if (!INSTALLED.compareAndSet(false, true)) {
      return;
    }
    TaintBridge.addObserver(
        new TaintBridge.SinkObserver() {
          @Override
          public void onFinding(
              String sourceLabel, String sinkKind, String argument, int start, int end) {
            StackTraceElement caller = callerFrame();
            InterceptorEvent.Builder event =
                InterceptorEvent.builder()
                    .type(EVENT_TYPE)
                    .plugin("iast")
                    .className(caller != null ? caller.getClassName() : sinkKind)
                    .methodName(caller != null ? caller.getMethodName() : sinkKind)
                    .attribute("source", sourceLabel)
                    .attribute("sink", sinkKind)
                    .attribute("argument", argument)
                    .attribute("start", Integer.toString(start))
                    .attribute("end", Integer.toString(end));
            if (caller != null) {
              event
                  .attribute("callerClass", caller.getClassName())
                  .attribute("callerMethod", caller.getMethodName())
                  .attribute("callerLine", Integer.toString(caller.getLineNumber()));
            }
            InterceptorEventPublisher.getInstance().publish(event.build());
          }
        });
  }

  /**
   * The first frame that is neither the taint package's own bookkeeping nor reflection glue, i.e.
   * the application code that reached the sink.
   */
  private static StackTraceElement callerFrame() {
    StackTraceElement[] frames = new Throwable().getStackTrace();
    for (StackTraceElement frame : frames) {
      String className = frame.getClassName();
      if (isInternal(className) || isLibrary(className)) {
        continue;
      }
      return frame;
    }
    return null;
  }

  private static boolean isInternal(String className) {
    for (String internal : INTERNAL_CLASSES) {
      if (className.equals(internal) || className.startsWith(internal + "$")) {
        return true;
      }
    }
    return false;
  }

  /**
   * JDK and framework frames between the sink and the application: advice inlined into a JDK class
   * reports that class as the frame, which is not where the developer needs to look.
   */
  private static boolean isLibrary(String className) {
    return className.startsWith("java.")
        || className.startsWith("javax.")
        || className.startsWith("jdk.")
        || className.startsWith("sun.")
        || className.startsWith("com.sun.")
        || className.startsWith("net.bytebuddy.");
  }
}
