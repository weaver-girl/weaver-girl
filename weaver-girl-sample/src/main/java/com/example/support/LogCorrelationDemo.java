package com.example.support;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Prints the same log line inside and outside an intercepted call, so the difference the logging
 * plugin makes is visible side by side:
 *
 * <pre>
 * without the agent            INFO  handling ticket 42
 * with the agent (logback)     INFO  [trace 4bf92f3577b34da6 span 00f067aa0ba902b7] handling ticket 42
 * </pre>
 *
 * <p>The application never references the agent or the plugin: it logs through SLF4J, and the
 * {@code logging} capability jar in {@code plugins/} is what puts the trace fields into the MDC
 * while an intercepted call runs. The log pattern is configured programmatically below, because
 * that is where {@code %X{traceId}} is read.
 *
 * <p>Exit code is 0 when the trace id reached the MDC inside the call, 2 when it did not (no
 * agent, or no logging plugin) — the same contract the other scenario demos use.
 */
public final class LogCorrelationDemo {

  private static final Logger log = LoggerFactory.getLogger(LogCorrelationDemo.class);

  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String SPAN_ID = "00f067aa0ba902b7";

  private LogCorrelationDemo() {}

  public static void main(String[] args) {
    installLogbackPattern();

    ThreadContext.put("traceId", TRACE_ID);
    ThreadContext.put("spanId", SPAN_ID);

    TicketService service = new TicketService();

    System.out.println("--- outside the intercepted call (MDC is not set yet) ---");
    log.info("startup complete");

    System.out.println("--- inside the intercepted call: the plugin bridges ThreadContext -> MDC ---");
    service.handleTicket(42);

    boolean correlated = TicketService.traceIdSeenInsideCall != null;
    System.out.println();
    System.out.println("trace id seen inside the call:  " + correlated);
    System.out.println("trace id seen outside the call: " + (MDC.get("traceId") != null));
    System.exit(correlated ? 0 : 2);
  }

  /**
   * Point logback's root logger at a console appender that prints the trace fields. A real
   * application sets the same pattern in {@code logback.xml}; doing it here keeps the demo to one
   * file. Any failure is ignored — the demo still prints its exit code honestly.
   */
  private static void installLogbackPattern() {
    try {
      ClassLoader loader = LogCorrelationDemo.class.getClassLoader();
      Class<?> contextClass = Class.forName("ch.qos.logback.classic.LoggerContext", false, loader);
      Object context = LoggerFactory.getILoggerFactory();
      if (!contextClass.isInstance(context)) {
        return;
      }
      Object root = contextClass.getMethod("getLogger", String.class).invoke(context, "ROOT");

      Class<?> encoderClass = Class.forName("ch.qos.logback.core.encoder.Encoder", false, loader);
      Class<?> appenderClass = Class.forName("ch.qos.logback.core.Appender", false, loader);
      Class<?> coreContext = Class.forName("ch.qos.logback.core.Context", false, loader);
      Class<?> layoutClass = Class.forName("ch.qos.logback.classic.PatternLayout", false, loader);

      Object layout = layoutClass.getConstructor().newInstance();
      layoutClass.getMethod("setContext", coreContext).invoke(layout, context);
      layoutClass
          .getMethod("setPattern", String.class)
          .invoke(layout, "%-5level [trace %X{traceId:-} span %X{spanId:-}] %msg%n");
      layoutClass.getMethod("start").invoke(layout);

      Object appender =
          Class.forName("ch.qos.logback.core.ConsoleAppender", false, loader)
              .getConstructor()
              .newInstance();
      appender.getClass().getMethod("setContext", coreContext).invoke(appender, context);
      // setLayout takes the Layout interface, not the concrete PatternLayout, so the method has to
      // be looked up by name and arity rather than by exact parameter type.
      java.lang.reflect.Method setLayout = null;
      for (java.lang.reflect.Method candidate : appender.getClass().getMethods()) {
        if ("setLayout".equals(candidate.getName()) && candidate.getParameterCount() == 1) {
          setLayout = candidate;
          break;
        }
      }
      if (setLayout == null) {
        return;
      }
      setLayout.invoke(appender, layout);
      appender.getClass().getMethod("start").invoke(appender);

      root.getClass().getMethod("detachAndStopAllAppenders").invoke(root);
      root.getClass().getMethod("addAppender", appenderClass).invoke(root, appender);
    } catch (Throwable ignored) {
      // Not logback, or an API that refused reflection: the demo still reports an honest exit code.
    }
  }
}
