package com.github.cc11001100.weavergirl.core.transformer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

/**
 * The default ignore rule excludes the framework's own packages, not its whole namespace.
 *
 * <p>It used to be a {@code nameStartsWith("com.github.cc11001100.weavergirl.")} prefix, which also
 * hid application code that happens to sit in that namespace — the bundled sample does, so every
 * hook it demonstrates was silently a no-op under {@code run-sample.sh}. Framing the list by package
 * keeps the framework unweavable while leaving application classes eligible.
 */
class AgentNamespaceAllowedTest {

  private static ElementMatcher<TypeDescription> matcher(boolean ignoreAgentClasses) {
    return WeaverTransformer.ignoredTypes(ignoreAgentClasses, Collections.<String>emptyList());
  }

  private static boolean ignored(String name) {
    return matcher(true).matches(new TypeDescription.Latent(name, 0, null));
  }

  @Test
  void frameworkPackagesStayUnweavable() {
    assertTrue(ignored("com.github.cc11001100.weavergirl.core.WeaverGirl"));
    assertTrue(ignored("com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer"));
    assertTrue(ignored("com.github.cc11001100.weavergirl.agent.WeaverGirlAgent"));
    assertTrue(ignored("com.github.cc11001100.weavergirl.api.plugin.PluginContext"));
    assertTrue(ignored("com.github.cc11001100.weavergirl.annotation.WeaveClass"));
    assertTrue(ignored("com.github.cc11001100.weavergirl.plugins.jdbc.JdbcPlugin"));
    assertTrue(ignored("com.github.cc11001100.weavergirl.runtime.HealthPlugin"));
    assertTrue(ignored("com.github.cc11001100.weavergirl.shade.org.slf4j.Logger"));
  }

  @Test
  void applicationCodeInTheSameNamespaceIsEligible() {
    assertFalse(ignored("com.github.cc11001100.weavergirl.sample.app.TargetService"));
    assertFalse(ignored("com.github.cc11001100.weavergirl.myproduct.AuditHook"));
    assertFalse(ignored("com.example.Foo"));
    assertFalse(ignored("probeapp.TargetService"));
  }

  @Test
  void jdkAndByteBuddyStayUnweavable() {
    assertTrue(ignored("sun.misc.Unsafe"));
    assertTrue(ignored("jdk.internal.misc.Unsafe"));
    assertTrue(ignored("com.sun.net.httpserver.HttpServer"));
    assertTrue(ignored(WeaverTransformer.byteBuddyPrefix() + "agent.ByteBuddyAgent"));
  }

  /** With {@code ignoreAgentClasses} off the framework tree must be weavable, as tests rely on. */
  @Test
  void turningTheFrameworkExclusionOffMakesCoreEligible() {
    ElementMatcher<TypeDescription> all = matcher(false);
    assertFalse(
        all.matches(
            new TypeDescription.Latent(
                "com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer", 0, null)));
    // The entries that are never rewritten regardless of the flag still apply.
    assertTrue(
        all.matches(
            new TypeDescription.Latent(
                "com.github.cc11001100.weavergirl.agent.WeaverGirlAgent", 0, null)));
  }
}
