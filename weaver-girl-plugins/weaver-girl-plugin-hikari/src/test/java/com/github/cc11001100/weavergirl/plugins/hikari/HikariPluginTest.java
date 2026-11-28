package com.github.cc11001100.weavergirl.plugins.hikari;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.MatchType;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.testkit.RecordingInterceptorRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HikariCP pool instrumentation: registration, config fallbacks, and event emission. */
class HikariPluginTest {

  private HikariPlugin plugin;
  private final List<InterceptorEvent> events = new ArrayList<>();
  private final com.github.cc11001100.weavergirl.api.event.InterceptorEventListener listener =
      events::add;

  @BeforeEach
  void setUp() {
    plugin = new HikariPlugin();
    InterceptorEventPublisher.getInstance().addListener(listener);
  }

  @AfterEach
  void tearDown() {
    InterceptorEventPublisher.getInstance().removeListener(listener);
  }

  @Test
  void nameAndLibrary() {
    assertEquals("hikari", plugin.name());
    assertEquals("com.zaxxer.hikari.pool.HikariPool", plugin.instrumentedLibrary());
  }

  @Test
  void registersPoolAcquireAndConnectionClose() {
    plugin.init(context(new HashMap<>()));
    List<InterceptorDefinition> defs = registerAndCollect();

    assertEquals(2, defs.size());
    assertEquals("hikari-pool-acquire", defs.get(0).getName());
    assertEquals("hikari-connection-close", defs.get(1).getName());
    for (InterceptorDefinition def : defs) {
      assertEquals(MatchType.SUPER_CLASS, def.getPointcut().getClassMatcher().getMatchType());
    }
  }

  @Test
  void initFallsBackForInvalidConfig() {
    Map<String, String> config = new HashMap<>();
    config.put("leakThresholdMs", "not-a-number");
    config.put("trackAcquisition", "maybe");

    plugin.init(context(config));

    assertEquals(2, registerAndCollect().size());
  }

  @Test
  void acquireCyclePublishesTimingEvent() {
    plugin.init(context(new HashMap<>()));
    InterceptorDefinition def = registerAndCollect().get(0);
    MethodInvocation invocation =
        new MethodInvocation(String.class, "getConnection", "target", new Object[] {});

    def.getInterceptor().before(invocation);
    def.getInterceptor().after(invocation);

    assertEquals(1, events.size());
    assertEquals("pool-acquire", events.get(0).getType());
    assertEquals("hikari", events.get(0).getPlugin());
    assertEquals("String", events.get(0).getAttributes().get("pool"));
  }

  @Test
  void acquisitionTrackingCanBeDisabled() {
    Map<String, String> config = new HashMap<>();
    config.put("trackAcquisition", "false");
    plugin.init(context(config));
    InterceptorDefinition def = registerAndCollect().get(0);
    MethodInvocation invocation =
        new MethodInvocation(String.class, "getConnection", "target", new Object[] {});

    def.getInterceptor().before(invocation);
    def.getInterceptor().after(invocation);

    assertTrue(events.isEmpty());
  }

  @Test
  void acquireFailurePublishesErrorEvent() {
    plugin.init(context(new HashMap<>()));
    InterceptorDefinition def = registerAndCollect().get(0);
    MethodInvocation failing =
        new MethodInvocation(String.class, "getConnection", "target", null);
    failing.setThrowable(new RuntimeException("pool exhausted"));

    def.getInterceptor().before(failing);
    def.getInterceptor().onException(failing);
    def.getInterceptor().after(failing);

    assertEquals(1, events.size());
    assertEquals("pool-acquire-error", events.get(0).getType());
  }

  @Test
  void closeCycleAndFailureDoNotThrow() {
    plugin.init(context(new HashMap<>()));
    InterceptorDefinition def = registerAndCollect().get(1);
    MethodInvocation invocation =
        new MethodInvocation(String.class, "close", "target", new Object[] {});

    def.getInterceptor().before(invocation);
    def.getInterceptor().after(invocation);
    assertFalse(events.isEmpty());
    assertEquals("connection-close", events.get(0).getType());

    MethodInvocation failing = new MethodInvocation(String.class, "close", "target", null);
    failing.setThrowable(new RuntimeException("closed"));
    assertDoesNotThrow(() -> def.getInterceptor().onException(failing));
  }

  /** Registers the plugin's interceptors and returns what it declared, for assertions. */
  private List<InterceptorDefinition> registerAndCollect() {
    RecordingInterceptorRegistry registry = new RecordingInterceptorRegistry();
    plugin.registerInterceptors(registry);
    return registry.getAllDefinitions();
  }

  private static PluginContext context(Map<String, String> config) {
    return RecordingInterceptorRegistry.context("hikari", config);
  }
}
