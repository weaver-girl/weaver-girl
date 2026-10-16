package com.github.cc11001100.weavergirl.core.transformer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.config.WeaverConfig;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.Arrays;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.Test;

/**
 * Installs a real {@link WeaverTransformer} against the definitions that only differ in how they
 * select classes, so every match type, the package allowlist and the transformation cap are built
 * rather than merely configured.
 */
class WeaverTransformerInstallTest {

  @Test
  void installCoversEveryMatchTypeAndThePackageAllowlist() {
    DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
    // A class outside the agent packages: retransform skips com.github.cc11001100.weavergirl., so a
    // target there would never reach the transform callback. This one is referenced by nothing else.
    Class<?> target =
        new ByteBuddy()
            .subclass(Object.class)
            // A fresh name every run: a class another test already transformed is not transformed
            // again, so this callback would never run and none of the matchers would be built.
            .name("wgtest.Target" + System.nanoTime())
            .make()
            .load(WeaverTransformerInstallTest.class.getClassLoader())
            .getLoaded();
    String self = target.getName();
    // The method matcher is built inside the transform callback, and only for the group that gets
    // past the cap. Every method match type therefore has to share this one group, or the cap stops
    // the callback before the matcher runs.
    registry.register(definition("any", ClassMatcher.byName(self), MethodMatcher.any()));
    registry.register(definition("named", ClassMatcher.byName(self), MethodMatcher.byName("toString")));
    registry.register(
        definition("namepattern", ClassMatcher.byName(self), MethodMatcher.byNamePattern("to.*")));
    registry.register(
        definition("annotated", ClassMatcher.byName(self), MethodMatcher.byAnnotation(self)));
    registry.register(
        definition("sig", ClassMatcher.byName(self), MethodMatcher.bySignature("x", "int")));
    registry.register(
        definition("sigbare", ClassMatcher.byName(self), MethodMatcher.bySignature("x", "")));
    registry.register(
        definition(
            "sigtyped",
            ClassMatcher.byName(self),
            MethodMatcher.bySignature("x", "java.lang.String,int")));
    registry.register(definition("ctor", ClassMatcher.byName(self), MethodMatcher.byConstructor()));
    registry.register(
        definition("ctorargs", ClassMatcher.byName(self), MethodMatcher.byConstructor("")));
    registry.register(
        definition(
            "ctortyped",
            ClassMatcher.byName(self),
            MethodMatcher.byConstructor("java.lang.String,int")));
    registry.register(definition("fieldget", ClassMatcher.byName(self), MethodMatcher.byFieldGet("value")));
    registry.register(definition("fieldset", ClassMatcher.byName(self), MethodMatcher.byFieldSet("value")));
    registry.register(
        definition(
            "args",
            ClassMatcher.byName(self),
            MethodMatcher.byArgumentAnnotation("com.example.Marked", 0)));
    // An exact name that is not loadable is reported and skipped before any matcher is built.
    registry.register(
        definition("missing", ClassMatcher.byName("com.example.DoesNotExist"), MethodMatcher.any()));
    // Each pattern names only the generated class. A pattern as wide as java.lang.Object makes
    // retransform rewrite every class already loaded, which takes half a minute and proves nothing more.
    registry.register(
        definition("pattern", ClassMatcher.byNamePattern(self.replace(".", "\\.")), MethodMatcher.any()));
    registry.register(definition("super", ClassMatcher.bySuperClass(self), MethodMatcher.any()));
    registry.register(definition("iface", ClassMatcher.byInterface(self), MethodMatcher.any()));
    registry.register(
        definition("field", ClassMatcher.byName(self), MethodMatcher.any(), InterceptorDefinition.AdviceMode.FIELD));
    registry.register(
        definition("catch", ClassMatcher.byName(self), MethodMatcher.any(), InterceptorDefinition.AdviceMode.CATCH));
    registry.register(
        definition(
            "rewrite",
            ClassMatcher.byName(self),
            MethodMatcher.any(),
            InterceptorDefinition.AdviceMode.ARGUMENT_REWRITE));

    WeaverConfig config = new WeaverConfig();
    // The allowlist is a name prefix, so it has to name the package the generated class lives in.
    config.setOnlyInterceptPackages(Arrays.asList("wgtest", "wgtest."));
    // One above the groups that share the exact-name matcher, so that group is woven and a later
    // group hits the cap instead.
    config.setMaxTransformations(2);

    WeaverTransformer transformer = new WeaverTransformer(registry);
    transformer.setWeaverConfig(config);
    transformer.setExcludedClassPatterns(Arrays.asList("wgtest\\.Excluded"));
    // Debug logging is off unless the property is set, and it is read on every callback, so switching it
    // on here reaches the branches that only log and restoring it afterwards changes nothing else.
    System.setProperty("weavergirl.debug", "true");
    try {
      // The first pass has to succeed, because a forced failure returns before any method matcher is
      // built. The two passes after it cover a failure rethrown as-is and one wrapped.
      transformer.install(ByteBuddyAgent.install(), false);
      int changed = transformer.retransformLoadedClasses();
      System.out.println("INSTALL-RETRANSFORM " + self + " changed=" + changed);
      assertTrue(changed >= 0);
      WeaverTransformer.failTransform(self, new RuntimeException("forced"));
      assertTrue(transformer.retransformLoadedClasses() >= 0);
      WeaverTransformer.failTransform(self, new Exception("forced checked"));
      assertTrue(transformer.retransformLoadedClasses() >= 0);
    } finally {
      WeaverTransformer.clearForcedFailures();
      System.clearProperty("weavergirl.debug");
    }
  }

  @Test
  void retransformWithoutAnInstallationDoesNothing() {
    WeaverTransformer transformer = new WeaverTransformer(new DefaultInterceptorRegistry());
    assertTrue(transformer.retransformLoadedClasses() == 0);
  }

  private static InterceptorDefinition definition(
      String name, ClassMatcher classes, MethodMatcher methods) {
    return definition(name, classes, methods, InterceptorDefinition.AdviceMode.STANDARD);
  }

  private static InterceptorDefinition definition(
      String name,
      ClassMatcher classes,
      MethodMatcher methods,
      InterceptorDefinition.AdviceMode mode) {
    return new InterceptorDefinition(
        name, new Pointcut(classes, methods), new Interceptor() {}, 0, mode);
  }
}
