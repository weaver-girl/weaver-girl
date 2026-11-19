package com.github.cc11001100.weavergirl.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.testkit.LateTargetService;
import com.example.testkit.TargetService;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A definition registered after the transformer was installed must still reach classes visited
 * afterwards.
 *
 * <p>The transformer registers its type/method matchers from the registry snapshot at install time,
 * so a definition added later used to fire on nothing: the config-watch reload path
 * ({@code retransformLoadedClasses}) could not pick it up, and a product could not add a hook at
 * runtime. This test is the regression net for that path.
 *
 * <p>It is deliberately written against the harness rather than a live agent: the bug lives in the
 * transformer's install logic, and the harness installs the same {@code WeaverTransformer}.
 */
class PluginTestHarnessLateDefinitionTest {

  @Test
  void aDefinitionRegisteredAfterInstallStillWeavesALaterVisit() throws ClassNotFoundException {
    PluginTestHarness harness;
    try {
      harness = PluginTestHarness.create();
    } catch (IllegalStateException noAttach) {
      assumeTrue(false, "this JVM does not allow self-attachment: " + noAttach.getMessage());
      return;
    }

    AtomicInteger earlyFired = new AtomicInteger();
    AtomicInteger lateFired = new AtomicInteger();

    try (PluginTestHarness h = harness) {
      h.registry()
          .register(
              new InterceptorDefinition(
                  "early",
                  new Pointcut(
                      ClassMatcher.byName(TargetService.class.getName()),
                      MethodMatcher.byName("greet")),
                  new Interceptor() {
                    @Override
                    public void before(MethodInvocation invocation) {
                      earlyFired.incrementAndGet();
                    }
                  }));

      // This call installs the transformer.
      h.weave(TargetService.class);
      new TargetService().greet("Ada");
      assertEquals(1, earlyFired.get(), "definition registered before install must fire");

      // Register a second definition after install, then visit a class that was already loaded
      // before the registration — the shape of a config-watch reload.
      Class.forName(LateTargetService.class.getName());
      h.registry()
          .register(
              new InterceptorDefinition(
                  "late",
                  new Pointcut(
                      ClassMatcher.byName(LateTargetService.class.getName()),
                      MethodMatcher.byName("touch")),
                  new Interceptor() {
                    @Override
                    public void before(MethodInvocation invocation) {
                      lateFired.incrementAndGet();
                    }
                  }));
      h.retransform(LateTargetService.class);
      new LateTargetService().touch("x");

      assertEquals(
          1,
          lateFired.get(),
          "a definition registered after install must still weave the next visit of its class");
    }
  }
}
