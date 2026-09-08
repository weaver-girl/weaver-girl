package com.github.cc11001100.weavergirl.plugins.spring;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spring Framework instrumentation plugin.
 *
 * <p>Two interception strategies:
 *
 * <ol>
 *   <li><strong>Controller handler methods:</strong> Intercepts methods annotated
 *       with @RequestMapping, @GetMapping, @PostMapping, etc. on @Controller/@RestController
 *       classes. This is the primary use case — tracking HTTP handler execution time.
 *   <li><strong>Service methods:</strong> Intercepts public methods on @Service classes, excluding
 *       Object methods (toString, hashCode, equals, etc.). This tracks business logic execution
 *       time.
 * </ol>
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowThreshold} — Slow method threshold in ms (default: 3000)
 *   <li>{@code logArguments} — Log method arguments (default: false)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 *
 * <p>Uses ClassMatcher.byAnnotation() so no compile dependency on Spring.
 */
public class SpringPlugin extends AbstractPlugin {

  private static final Logger log = LoggerFactory.getLogger(SpringPlugin.class);

  private long slowThresholdMs = 3000;
  private boolean logArguments = false;
  private boolean enabled = true;

  // Controller class annotations
  private static final String[] CONTROLLER_ANNOTATIONS = {
    "org.springframework.stereotype.Controller",
    "org.springframework.web.bind.annotation.RestController"
  };

  // Handler method annotations (on @Controller classes)
  private static final String[] HANDLER_METHOD_ANNOTATIONS = {
    "org.springframework.web.bind.annotation.RequestMapping",
    "org.springframework.web.bind.annotation.GetMapping",
    "org.springframework.web.bind.annotation.PostMapping",
    "org.springframework.web.bind.annotation.PutMapping",
    "org.springframework.web.bind.annotation.DeleteMapping",
    "org.springframework.web.bind.annotation.PatchMapping"
  };

  // Service class annotations
  private static final String[] SERVICE_ANNOTATIONS = {
    "org.springframework.stereotype.Service", "org.springframework.stereotype.Repository"
  };

  // Methods to exclude from service interception (Object methods)
  private static final String EXCLUDED_METHODS_REGEX =
      "^(toString|hashCode|equals|getClass|notify|notifyAll|wait|clone|finalize)$";

  @Override
  public String name() {
    return "spring";
  }

  @Override
  public void init(PluginContext context) {
    slowThresholdMs = context.getConfigLong("slowThreshold", 3000);
    logArguments = context.getConfigBoolean("logArguments", false);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(
      com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
    if (!enabled) return;

    Interceptor springInterceptor =
        new Interceptor() {
          private final ThreadLocal<Long> startTime = new ThreadLocal<>();

          @Override
          public void before(MethodInvocation inv) {
            startTime.set(System.nanoTime());
            if (logArguments && log.isDebugEnabled()) {
              StringBuilder args = new StringBuilder();
              Object[] methodArgs = inv.getArguments();
              if (methodArgs != null) {
                for (int i = 0; i < methodArgs.length; i++) {
                  if (i > 0) args.append(", ");
                  args.append(methodArgs[i] != null ? methodArgs[i].toString() : "null");
                }
              }
              log.debug(
                  "[SPRING] {}.{}({})",
                  inv.getTargetClass().getSimpleName(),
                  inv.getMethodName(),
                  args);
            }
          }

          @Override
          public void after(MethodInvocation inv) {
            Long start = startTime.get();
            startTime.remove();
            if (start != null) {
              long elapsedMs = (System.nanoTime() - start) / 1_000_000;
              if (elapsedMs >= slowThresholdMs) {
                log.warn(
                    "[SLOW-SPRING] {}.{} took {}ms (threshold: {}ms)",
                    inv.getTargetClass().getSimpleName(),
                    inv.getMethodName(),
                    elapsedMs,
                    slowThresholdMs);
                InterceptorEventPublisher.getInstance()
                    .publish(
                        InterceptorEvent.builder()
                            .type("slow-spring")
                            .plugin("spring")
                            .className(inv.getTargetClass().getSimpleName())
                            .methodName(inv.getMethodName())
                            .durationMs(elapsedMs)
                            .build());
              } else if (log.isDebugEnabled()) {
                log.debug(
                    "[SPRING] {}.{} took {}ms",
                    inv.getTargetClass().getSimpleName(),
                    inv.getMethodName(),
                    elapsedMs);
              }
            }
          }

          @Override
          public void onException(MethodInvocation inv) {
            startTime.remove();
            log.warn(
                "[SPRING-ERROR] {}.{} threw: {}",
                inv.getTargetClass().getSimpleName(),
                inv.getMethodName(),
                inv.getThrowable().getMessage());
            InterceptorEventPublisher.getInstance()
                .publish(
                    InterceptorEvent.builder()
                        .type("spring-error")
                        .plugin("spring")
                        .className(inv.getTargetClass().getSimpleName())
                        .methodName(inv.getMethodName())
                        .attribute("error", inv.getThrowable().getMessage())
                        .build());
          }
        };

    // Strategy 1: Intercept handler methods on @Controller/@RestController classes
    // Only intercept methods with @RequestMapping, @GetMapping, etc.
    for (String classAnnotation : CONTROLLER_ANNOTATIONS) {
      for (String methodAnnotation : HANDLER_METHOD_ANNOTATIONS) {
        registry.register(
            interceptAnnotated(classAnnotation)
                .methodAnnotated(methodAnnotation)
                .around(inv -> springInterceptor.before(inv), inv -> springInterceptor.after(inv))
                .priority(10)
                .build());
      }
    }

    // Strategy 2: Intercept public methods on @Service/@Repository classes
    // Exclude Object methods to avoid noise
    for (String annotation : SERVICE_ANNOTATIONS) {
      registry.register(
          interceptAnnotated(annotation)
              .methodPattern(
                  "^(?!toString$|hashCode$|equals$|getClass$|notify$|notifyAll$|wait$|clone$|finalize$).+")
              .around(inv -> springInterceptor.before(inv), inv -> springInterceptor.after(inv))
              .priority(20)
              .build());
    }
  }
}
