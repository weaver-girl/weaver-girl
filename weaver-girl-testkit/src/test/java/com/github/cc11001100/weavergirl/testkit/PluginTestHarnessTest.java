package com.github.cc11001100.weavergirl.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.testkit.TargetService;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises the harness against a real class, including that closing it restores the JVM. */
class PluginTestHarnessTest {

  /** A product-style plugin that records what it saw. */
  static final class RecordingPlugin extends AbstractPlugin {
    final List<String> events = new ArrayList<String>();
    long threshold = -1;
    boolean enabled = true;

    @Override
    public String name() {
      return "recording-plugin";
    }

    @Override
    public void init(PluginContext context) {
      threshold = context.getConfigLong("threshold", 1000L);
    }

    @Override
    public boolean isEnabled(PluginContext context) {
      return enabled;
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
      registry.register(
          intercept(TargetService.class.getName())
              .method("greet")
              .before(inv -> events.add("before:" + inv.getArgument(0)))
              .after(inv -> events.add("after:" + inv.getReturnValue()))
              .onException(inv -> events.add("error"))
              .build());

      registry.register(
          interceptField(TargetService.class.getName(), "secret")
              .name("secret-get")
              .onGet((invocation, value) -> invocation.setReturnValue("masked"))
              .build());
    }
  }

  /** A plugin that is switched off by configuration. */
  static final class DisabledPlugin extends AbstractPlugin {
    @Override
    public String name() {
      return "disabled-plugin";
    }

    @Override
    public boolean isEnabled(PluginContext context) {
      return false;
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
      registry.register(intercept(TargetService.class.getName()).anyMethod().build());
    }
  }

  private static PluginTestHarness harness() {
    try {
      return PluginTestHarness.create();
    } catch (IllegalStateException noAttach) {
      assumeTrue(false, "Instrumentation attach unavailable: " + noAttach.getMessage());
      throw noAttach;
    }
  }

  @Test
  void methodCallbacksFireAndCompose() {
    RecordingPlugin plugin = new RecordingPlugin();
    try (PluginTestHarness harness =
        harness().config("recording-plugin", "threshold", "42").install(plugin)) {

      assertEquals(42, plugin.threshold, "scoped config reaches init");
      assertTrue(harness.isActive("recording-plugin"));

      harness.weave(TargetService.class);

      assertEquals("hello Ada", new TargetService().greet("Ada"));
      assertEquals(
          java.util.Arrays.asList("before:Ada", "after:hello Ada"), plugin.events);
    }
  }

  /** Only a field definition, to tell a field-weaving problem from an interaction between groups. */
  static final class FieldOnlyPlugin extends AbstractPlugin {
    final List<String> reads = new ArrayList<String>();

    @Override
    public String name() {
      return "field-only";
    }

    @Override
    public void registerInterceptors(InterceptorRegistry registry) {
      registry.register(
          interceptField(TargetService.class.getName(), "secret")
              .onGet(
                  (invocation, value) -> {
                    reads.add(invocation.getFieldName());
                    invocation.setReturnValue("masked");
                  })
              .build());
    }
  }

  @Test
  void fieldOnlyPluginInterceptsAccessors() {
    FieldOnlyPlugin plugin = new FieldOnlyPlugin();
    try (PluginTestHarness harness = harness().install(plugin)) {
      harness.weave(TargetService.class);
      assertEquals("masked", new TargetService().getSecret());
      assertEquals(java.util.Collections.singletonList("secret"), plugin.reads);
    }
  }

  @Test
  void fieldReadsAreInterceptedAndWritesAreNot() {
    RecordingPlugin plugin = new RecordingPlugin();
    try (PluginTestHarness harness = harness().install(plugin)) {
      harness.weave(TargetService.class);

      TargetService target = new TargetService();
      assertEquals("masked", target.getSecret(), "getfield goes through onGet");
      target.setSecret("changed");
      assertEquals("changed", target.secret, "a write with no onSet callback is untouched");
    }
  }

  @Test
  void disabledPluginRegistersNothing() {
    try (PluginTestHarness harness = harness().install(new DisabledPlugin())) {
      assertFalse(harness.isActive("disabled-plugin"));
      assertTrue(harness.registry().getAllDefinitions().isEmpty());
      assertThrows(IllegalStateException.class, () -> harness.weave(TargetService.class));
    }
  }

  @Test
  void weaveRejectsAClassNoDefinitionMatches() {
    try (PluginTestHarness harness = harness().install(new RecordingPlugin())) {
      IllegalStateException failure =
          assertThrows(IllegalStateException.class, () -> harness.weave(String.class));
      assertTrue(failure.getMessage().contains("recording-plugin-"));
    }
  }

  @Test
  void installingAfterWeavingIsRejected() {
    try (PluginTestHarness harness =
        harness().install(new RecordingPlugin()).weave(TargetService.class)) {
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class, () -> harness.install(new DisabledPlugin()));
      assertTrue(failure.getMessage().contains("Install every plugin first"));
    }
  }

  @Test
  void closeRestoresTheGlobalStateAndTheClass() {
    InterceptorHolder.setRegistry(null);

    RecordingPlugin plugin = new RecordingPlugin();
    PluginTestHarness harness = harness().install(plugin);
    harness.weave(TargetService.class);
    assertEquals("hello Ada", new TargetService().greet("Ada"));
    assertEquals(2, plugin.events.size());

    harness.close();
    harness.close();

    assertEquals(null, InterceptorHolder.getRegistry(), "the previous registry comes back");
    assertEquals("hello Ada", new TargetService().greet("Ada"));
    assertEquals(2, plugin.events.size(), "the restored class no longer calls the plugin");
  }
}
