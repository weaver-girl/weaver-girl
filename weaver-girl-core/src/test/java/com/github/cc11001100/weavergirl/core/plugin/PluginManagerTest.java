package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.plugin.PluginInfo;
import com.github.cc11001100.weavergirl.api.plugin.PluginManager;
import com.github.cc11001100.weavergirl.api.plugin.PluginState;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.*;
import org.junit.jupiter.api.*;

/** Tests for the Plugin Manager (P47). */
class PluginManagerTest {

  private DefaultInterceptorRegistry registry;
  private PluginLoader pluginLoader;
  private DefaultPluginManager pluginManager;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
    pluginLoader = new PluginLoader();
  }

  private void initPluginManager(Map<String, String> config) {
    // Manually create a test plugin and register it
    pluginLoader.loadPlugins(getClass().getClassLoader(), registry, config);
    pluginManager = new DefaultPluginManager(pluginLoader, registry);
  }

  // ===== PluginState transitions =====

  @Test
  void pluginState_transitions() {
    assertTrue(PluginState.LOADED.canTransitionTo(PluginState.ACTIVE));
    assertTrue(PluginState.LOADED.canTransitionTo(PluginState.UNLOADED));
    assertFalse(PluginState.LOADED.canTransitionTo(PluginState.DISABLED));

    assertTrue(PluginState.ACTIVE.canTransitionTo(PluginState.DISABLED));
    assertTrue(PluginState.ACTIVE.canTransitionTo(PluginState.UNLOADED));
    assertFalse(PluginState.ACTIVE.canTransitionTo(PluginState.LOADED));

    assertTrue(PluginState.DISABLED.canTransitionTo(PluginState.ACTIVE));
    assertTrue(PluginState.DISABLED.canTransitionTo(PluginState.UNLOADED));
    assertFalse(PluginState.DISABLED.canTransitionTo(PluginState.LOADED));

    // UNLOADED is terminal
    assertFalse(PluginState.UNLOADED.canTransitionTo(PluginState.ACTIVE));
    assertFalse(PluginState.UNLOADED.canTransitionTo(PluginState.DISABLED));
    assertFalse(PluginState.UNLOADED.canTransitionTo(PluginState.LOADED));
    assertFalse(PluginState.UNLOADED.canTransitionTo(PluginState.UNLOADED));
  }

  // ===== Disable/Enable plugin =====

  @Test
  void disablePlugin_unknownPlugin_returnsFalse() {
    initPluginManager(new HashMap<>());
    assertFalse(pluginManager.disablePlugin("non-existent"));
  }

  @Test
  void enablePlugin_unknownPlugin_returnsFalse() {
    initPluginManager(new HashMap<>());
    assertFalse(pluginManager.enablePlugin("non-existent"));
  }

  @Test
  void pluginInfo_returnsInfoForLoadedPlugins() {
    initPluginManager(new HashMap<>());

    List<PluginInfo> allInfo = pluginManager.getAllPluginInfo();
    // Plugins may or may not be loaded depending on test classpath
    for (PluginInfo info : allInfo) {
      assertNotNull(info.getName());
      assertEquals(PluginState.ACTIVE, info.getState());
      assertTrue(info.getLoadedAt() > 0);
    }
  }

  @Test
  void isPluginActive_checksState() {
    initPluginManager(new HashMap<>());

    // Check built-in plugins are active
    List<PluginInfo> allInfo = pluginManager.getAllPluginInfo();
    if (!allInfo.isEmpty()) {
      String firstPlugin = allInfo.get(0).getName();
      assertTrue(pluginManager.isPluginActive(firstPlugin));
    }

    assertFalse(pluginManager.isPluginActive("non-existent"));
  }

  @Test
  void getActivePluginCount_matchesActivePlugins() {
    initPluginManager(new HashMap<>());
    int activeCount = pluginManager.getActivePluginCount();
    assertEquals(pluginManager.getAllPluginInfo().size(), activeCount);
  }

  @Test
  void getPluginInfo_returnsEmptyForUnknown() {
    initPluginManager(new HashMap<>());
    assertFalse(pluginManager.getPluginInfo("non-existent").isPresent());
  }

  // ===== PluginManager.getInstance() =====

  @Test
  void getInstance_returnsCreatedManager() {
    initPluginManager(new HashMap<>());
    PluginManager instance = PluginManager.getInstance();
    assertNotNull(instance);
    assertSame(pluginManager, instance);
  }

  // ===== Manual plugin disable/enable lifecycle =====

  @Test
  void disableAndEnable_lifecycle() {
    // Create a simple registry and plugin manager manually
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    PluginLoader loader = new PluginLoader();

    // Register a test interceptor
    Interceptor testInterceptor = new Interceptor() {};
    InterceptorDefinition def =
        new InterceptorDefinition(
            "test-plugin-myMethod",
            new Pointcut(ClassMatcher.byName("com.example.Test"), MethodMatcher.byName("myMethod")),
            testInterceptor,
            0);
    reg.register(def);

    // Manually add a plugin to the loader
    // Since we can't easily inject into PluginLoader, test with what's loaded
    DefaultPluginManager pm = new DefaultPluginManager(loader, reg);

    // With no plugins loaded, all operations should return false
    assertFalse(pm.disablePlugin("anything"));
    assertEquals(0, pm.getActivePluginCount());
    assertTrue(pm.getAllPluginInfo().isEmpty());
  }

  // ===== WeaverPlugin interface =====

  @Test
  void weaverPlugin_defaultMethods() {
    WeaverPlugin plugin =
        new WeaverPlugin() {
          @Override
          public String name() {
            return "test";
          }

          @Override
          public void registerInterceptors(InterceptorRegistry r) {}
        };

    // Default implementations
    assertArrayEquals(new String[0], plugin.depends());
    assertTrue(plugin.isEnabled(null));
    assertDoesNotThrow(() -> plugin.init(null));
    assertDoesNotThrow(() -> plugin.destroy());
  }

  // ===== PluginInfo =====

  @Test
  void pluginInfo_toString() {
    PluginInfo info =
        new PluginInfo(
            "test",
            PluginState.ACTIVE,
            "1.0",
            3,
            System.currentTimeMillis(),
            System.currentTimeMillis());
    String str = info.toString();
    assertTrue(str.contains("test"));
    assertTrue(str.contains("ACTIVE"));
    assertTrue(str.contains("3"));
  }

  @Test
  void managedPlugin_supportsDisableEnableUnloadAndFailures() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    InterceptorDefinition definition =
        new InterceptorDefinition(
            "managed-hook", new Pointcut(ClassMatcher.byName("x"), MethodMatcher.any()), new Interceptor() {});
    reg.register(definition);
    class TestPlugin implements WeaverPlugin {
      boolean destroyed;
      boolean failRegister;
      public String name() { return "managed"; }
      public void registerInterceptors(InterceptorRegistry r) {
        if (failRegister) throw new IllegalStateException("register");
      }
      public void destroy() { destroyed = true; }
    }
    TestPlugin plugin = new TestPlugin();
    PluginLoader loader = new PluginLoader() {
      public List<WeaverPlugin> getLoadedPlugins() { return Collections.singletonList(plugin); }

      @Override
      public List<WeaverPlugin> unloadDependentsOf(java.util.Collection<WeaverPlugin> unloaded) {
        for (WeaverPlugin plugin : unloaded) {
          plugin.destroy();
        }
        return new ArrayList<>(unloaded);
      }
    };
    DefaultPluginManager pm = new DefaultPluginManager(loader, reg);
    assertTrue(pm.isPluginActive("managed"));
    assertFalse(pm.disablePlugin("missing"));
    assertTrue(pm.disablePlugin("managed"));
    assertFalse(pm.isPluginActive("managed"));
    assertFalse(pm.disablePlugin("managed"));
    plugin.failRegister = true;
    assertFalse(pm.enablePlugin("managed"));
    plugin.failRegister = false;
    assertTrue(pm.enablePlugin("managed"));
    assertTrue(pm.unloadPlugin("managed"));
    assertTrue(plugin.destroyed);
    assertFalse(pm.enablePlugin("managed"));
    assertFalse(pm.unloadPlugin("missing"));
    // Already unloaded, so there is no state it can move to from here.
    assertFalse(pm.unloadPlugin("managed"), "an unloaded plugin was unloaded again");
  }

  /** A lifecycle event that fails to publish is logged and does not undo the change. */
  @Test
  void aLifecycleEventThatCannotBePublishedDoesNotUndoTheChange() throws Exception {
    java.lang.reflect.Field current =
        com.github.cc11001100.weavergirl.api.tracing.Tracer.class.getDeclaredField("CURRENT_SPAN");
    Object real = com.github.cc11001100.weavergirl.core.testsupport.Statics.read(current, null);
    com.github.cc11001100.weavergirl.core.testsupport.Statics.write(current, null, failingSpan());
    try {
      DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
      WeaverPlugin plugin = new WeaverPlugin() {
        public String name() { return "watched"; }
        public void registerInterceptors(InterceptorRegistry r) {}
      };
      PluginLoader loader = new PluginLoader() {
        public List<WeaverPlugin> getLoadedPlugins() { return Collections.singletonList(plugin); }

        @Override
        public List<WeaverPlugin> unloadDependentsOf(java.util.Collection<WeaverPlugin> unloaded) {
          return new ArrayList<>(unloaded);
        }
      };
      DefaultPluginManager pm = new DefaultPluginManager(loader, reg);

      assertTrue(pm.disablePlugin("watched"));
      assertTrue(pm.enablePlugin("watched"));
      assertTrue(pm.unloadPlugin("watched"));
      assertFalse(pm.isPluginActive("watched"));
    } finally {
      com.github.cc11001100.weavergirl.core.testsupport.Statics.write(current, null, real);
    }
  }

  /** The current span holder, except reading it fails outright. */
  private static ThreadLocal<Object> failingSpan() {
    return new ThreadLocal<Object>() {
      @Override
      public Object get() {
        throw new OutOfMemoryError("span unavailable");
      }
    };
  }

  /**
   * The registry handed to a plugin while it is being re-enabled delegates every call and records the
   * names. A plugin that unregisters, then asks what is registered, exercises the whole of it.
   */
  @Test
  void enablePlugin_recordsNamesThroughTheRegistryItHandsOut() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    InterceptorDefinition definition =
        new InterceptorDefinition(
            "chatty-hook",
            new Pointcut(ClassMatcher.byName("x"), MethodMatcher.any()),
            new Interceptor() {});
    class ChattyPlugin implements WeaverPlugin {
      public String name() {
        return "chatty";
      }

      public void registerInterceptors(InterceptorRegistry r) {
        r.register(definition);
        r.register(null);
        assertTrue(r.unregister("chatty-hook"));
        assertFalse(r.unregister("chatty-hook"));
        assertTrue(r.getInterceptorsForClass("x").isEmpty());
        assertTrue(r.getAllDefinitions().isEmpty());
        r.register(definition);
      }

      public void destroy() {}
    }
    ChattyPlugin plugin = new ChattyPlugin();
    PluginLoader loader =
        new PluginLoader() {
          public List<WeaverPlugin> getLoadedPlugins() {
            return Collections.singletonList(plugin);
          }
        };
    DefaultPluginManager pm = new DefaultPluginManager(loader, reg);
    assertTrue(pm.disablePlugin("chatty"));
    assertTrue(pm.enablePlugin("chatty"));
    assertEquals(Collections.singletonList("chatty-hook"), namesOf(reg));
  }

  private static List<String> namesOf(DefaultInterceptorRegistry reg) {
    List<String> names = new ArrayList<>();
    for (InterceptorDefinition def : reg.getAllDefinitions()) {
      names.add(def.getName());
    }
    return names;
  }

  /**
   * Disabling one plugin must not remove another's interceptors just because their names share a
   * prefix.
   *
   * <p>Ownership used to be guessed from the interceptor name: anything starting with the plugin name,
   * or containing {@code -name-}, was treated as belonging to it. Generated names are {@code
   * pluginName + "-" + class + "-" + method}, so disabling {@code jdbc} also matched {@code
   * jdbc-pool}'s interceptors and tore them out.
   */
  @Test
  void disablePlugin_doesNotRemoveInterceptorsOfAPluginWhoseNameExtendsIt() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    reg.register(named("jdbc-java.sql.Statement-execute"));
    reg.register(named("jdbc-pool-com.zaxxer.hikari.HikariDataSource-getConnection"));

    class NamedPlugin implements WeaverPlugin {
      final String pluginName;

      NamedPlugin(String pluginName) {
        this.pluginName = pluginName;
      }

      public String name() {
        return pluginName;
      }

      public void registerInterceptors(InterceptorRegistry r) {}

      public void destroy() {}
    }

    WeaverPlugin jdbc = new NamedPlugin("jdbc");
    WeaverPlugin pool = new NamedPlugin("jdbc-pool");
    StubLoader loader = new StubLoader(jdbc, pool);
    loader.ownership().record(jdbc, "jdbc-java.sql.Statement-execute");
    loader.ownership().record(pool, "jdbc-pool-com.zaxxer.hikari.HikariDataSource-getConnection");
    DefaultPluginManager pm = new DefaultPluginManager(loader, reg);

    assertTrue(pm.disablePlugin("jdbc"));

    assertTrue(
        reg.getAllDefinitions().stream()
            .noneMatch(d -> d.getName().equals("jdbc-java.sql.Statement-execute")),
        "disabling jdbc left its own interceptor registered");
    assertTrue(
        reg.getAllDefinitions().stream()
            .anyMatch(d -> d.getName().equals("jdbc-pool-com.zaxxer.hikari.HikariDataSource-getConnection")),
        "disabling jdbc also removed jdbc-pool's interceptor");
    assertEquals(
        1,
        pm.getPluginInfo("jdbc-pool").get().getInterceptorCount(),
        "jdbc-pool's reported interceptor count was reduced by disabling jdbc");
  }

  /**
   * Field and catch definitions are named differently from method ones: the kind word sits between the
   * plugin name and the class, as in {@code plugin-field-com.example.Type-amount}. The class name is
   * still what ends the plugin name, so the same boundary has to hold for them.
   */
  @Test
  void disablePlugin_keepsFieldAndCatchInterceptorsOfAnotherPlugin() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    reg.register(named("jdbc-field-java.sql.Connection-catalog"));
    reg.register(named("jdbc-catch-java.sql.Statement-java.sql.SQLException"));
    reg.register(named("jdbc-pool-field-com.zaxxer.hikari.HikariDataSource-poolSize"));

    WeaverPlugin jdbc = namedPlugin("jdbc");
    WeaverPlugin pool = namedPlugin("jdbc-pool");
    StubLoader loader = new StubLoader(jdbc, pool);
    loader.ownership().record(jdbc, "jdbc-field-java.sql.Connection-catalog");
    loader.ownership().record(jdbc, "jdbc-catch-java.sql.Statement-java.sql.SQLException");
    loader.ownership().record(pool, "jdbc-pool-field-com.zaxxer.hikari.HikariDataSource-poolSize");
    DefaultPluginManager pm = new DefaultPluginManager(loader, reg);

    assertTrue(pm.disablePlugin("jdbc"));

    List<String> remaining = new ArrayList<>();
    for (InterceptorDefinition def : reg.getAllDefinitions()) {
      remaining.add(def.getName());
    }
    assertEquals(
        Collections.singletonList("jdbc-pool-field-com.zaxxer.hikari.HikariDataSource-poolSize"),
        remaining);
  }

  /**
   * A hand-written interceptor name need not contain a class name, so it has no dot to recover the
   * plugin name from. It still belongs to the plugin that registered it, and disabling that plugin
   * has to remove it.
   */
  @Test
  void disablePlugin_removesInterceptorsWhoseNameHasNoClassInIt() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    reg.register(named("audit-hook"));
    reg.register(named("metrics-hook"));

    RecordingPlugin audit = new RecordingPlugin("audit", "audit-hook");
    RecordingPlugin metrics = new RecordingPlugin("metrics", "metrics-hook");
    StubLoader loader = new StubLoader(audit, metrics);
    loader.ownership().record(audit, "audit-hook");
    loader.ownership().record(metrics, "metrics-hook");
    DefaultPluginManager pm = new DefaultPluginManager(loader, reg);

    assertTrue(pm.disablePlugin("audit"));

    List<String> remaining = new ArrayList<>();
    for (InterceptorDefinition def : reg.getAllDefinitions()) {
      remaining.add(def.getName());
    }
    assertEquals(
        Collections.singletonList("metrics-hook"),
        remaining,
        "an interceptor whose name has no class in it survived its plugin being disabled");
    assertTrue(pm.enablePlugin("audit"));
    assertEquals(2, reg.getAllDefinitions().size(), "re-enabling the plugin did not restore its interceptor");
  }

  /**
   * Some plugins register from {@code init} through the registry handed out by the context, not from
   * {@code registerInterceptors}. The annotation scanner does exactly this. Those interceptors belong
   * to the plugin too, and disabling it has to remove them even though their names say nothing about
   * which plugin added them.
   */
  @Test
  void disablePlugin_removesInterceptorsRegisteredThroughTheContext() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();

    class ContextRegisteringPlugin implements WeaverPlugin {
      final String pluginName;
      final String interceptorName;

      ContextRegisteringPlugin(String pluginName, String interceptorName) {
        this.pluginName = pluginName;
        this.interceptorName = interceptorName;
      }

      public String name() {
        return pluginName;
      }

      public void init(com.github.cc11001100.weavergirl.api.plugin.PluginContext context) {
        context.getRegistry().register(named(interceptorName));
      }

      public void registerInterceptors(InterceptorRegistry r) {}

      public void destroy() {}
    }

    ContextRegisteringPlugin scanner = new ContextRegisteringPlugin("annotation-scan", "scanned-hook");
    ContextRegisteringPlugin other = new ContextRegisteringPlugin("metrics", "metrics-hook");
    StubLoader loader = new StubLoader(scanner, other);
    for (ContextRegisteringPlugin plugin : Arrays.asList(scanner, other)) {
      plugin.init(
          new DefaultPluginContext(loader.ownership().recording(plugin, reg), null, plugin.name()));
    }

    DefaultPluginManager pm = new DefaultPluginManager(loader, reg, loader.ownership());
    assertTrue(pm.disablePlugin("annotation-scan"));

    List<String> remaining = new ArrayList<>();
    for (InterceptorDefinition def : reg.getAllDefinitions()) {
      remaining.add(def.getName());
    }
    assertEquals(
        Collections.singletonList("metrics-hook"),
        remaining,
        "an interceptor registered through the plugin context survived its plugin being disabled");
  }

  /** A plugin that registers exactly one named interceptor. */
  private static final class RecordingPlugin implements WeaverPlugin {
    final String pluginName;
    final String interceptorName;

    RecordingPlugin(String pluginName, String interceptorName) {
      this.pluginName = pluginName;
      this.interceptorName = interceptorName;
    }

    public String name() {
      return pluginName;
    }

    public void registerInterceptors(InterceptorRegistry r) {
      r.register(named(interceptorName));
    }

    public void destroy() {}
  }

  /**
   * Building the manager used to replay every plugin's {@code registerInterceptors} so it could learn
   * which interceptor belonged to which plugin. Registration runs {@code initialize()} on the
   * interceptor, so an interceptor that had already been initialized while the plugin loaded was
   * initialized a second time — and the replayed definition replaced the one actually loaded.
   */
  @Test
  void buildingTheManagerDoesNotInitializeInterceptorsAgain() {
    int[] initialized = {0};
    Interceptor interceptor =
        new Interceptor() {
          @Override
          public void initialize() {
            initialized[0]++;
          }
        };
    InterceptorDefinition definition =
        new InterceptorDefinition(
            "audit-hook", new Pointcut(ClassMatcher.byName("x"), MethodMatcher.any()), interceptor);

    class CountingPlugin implements WeaverPlugin {
      public String name() {
        return "audit";
      }

      public void registerInterceptors(InterceptorRegistry r) {
        r.register(definition);
      }

      public void destroy() {}
    }

    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    CountingPlugin plugin = new CountingPlugin();
    StubLoader loader = new StubLoader(plugin);
    plugin.registerInterceptors(loader.ownership().recording(plugin, reg));
    assertEquals(1, initialized[0], "loading the plugin did not initialize its interceptor");

    new DefaultPluginManager(loader, reg);

    assertEquals(
        1, initialized[0], "building the plugin manager initialized an interceptor a second time");
    assertSame(
        definition,
        reg.getAllDefinitions().get(0),
        "building the plugin manager replaced the loaded interceptor definition");
  }

  /**
   * Two plugins can be named alike, and the manager used to keep one entry per name. Disabling the name
   * then destroyed both plugins while reporting a single one, and the second plugin's interceptors went
   * with the first because ownership was recorded by name too.
   */
  @Test
  void disablePlugin_removesOnlyTheInterceptorsOfTheNamedInstance() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();

    RecordingPlugin first = new RecordingPlugin("metrics", "metrics-first");
    RecordingPlugin second = new RecordingPlugin("metrics", "metrics-second");
    StubLoader loader = new StubLoader(first, second);
    loader.ownership().record(first, "metrics-first");
    loader.ownership().record(second, "metrics-second");
    reg.register(named("metrics-first"));
    reg.register(named("metrics-second"));

    DefaultPluginManager pm = new DefaultPluginManager(loader, reg);

    assertEquals(2, pm.getActivePluginCount(), "two plugins of one name counted as one");
    assertTrue(pm.disablePlugin("metrics"));

    assertTrue(
        reg.getAllDefinitions().isEmpty(),
        "disabling a shared name left one plugin's interceptors registered");
    assertFalse(pm.isPluginActive("metrics"));
    assertEquals(0, pm.getPluginInfo("metrics").get().getInterceptorCount());
  }

  /**
   * Disabling a plugin suspends the one that requires it, and enabling it again brings that one back. A plugin
   * that was disabled on its own is not suspended, so it stays disabled.
   */
  @Test
  void disablingAPluginSuspendsTheOneThatRequiresIt() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    reg.register(named("base-hook"));
    reg.register(named("follower-hook"));

    RecordingPlugin base = new RecordingPlugin("base", "base-hook");
    RecordingPlugin follower = new RecordingPlugin("follower", "follower-hook");
    RecordingPlugin onItsOwn = new RecordingPlugin("loner", "loner-hook");
    StubLoader loader = new StubLoader(base, follower, onItsOwn).requires(follower, "base");
    loader.ownership().record(base, "base-hook");
    loader.ownership().record(follower, "follower-hook");
    DefaultPluginManager pm = new DefaultPluginManager(loader, reg);

    assertTrue(pm.disablePlugin("base"));
    assertFalse(pm.isPluginActive("follower"), "a plugin requiring a disabled one stayed active");
    assertTrue(
        reg.getAllDefinitions().stream().noneMatch(d -> d.getName().equals("follower-hook")),
        "the suspended plugin kept its interceptor");

    assertTrue(pm.enablePlugin("base"));
    assertTrue(pm.isPluginActive("follower"), "re-enabling the requirement did not resume the plugin");
    assertTrue(
        reg.getAllDefinitions().stream().anyMatch(d -> d.getName().equals("follower-hook")),
        "the resumed plugin did not register its interceptor again");
  }

  /** A plugin suspended because two requirements are unmet stays suspended until both are back. */
  @Test
  void aPluginStaysSuspendedWhileAnotherRequirementIsStillDisabled() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    RecordingPlugin base = new RecordingPlugin("base", "base-hook");
    RecordingPlugin other = new RecordingPlugin("other", "other-hook");
    RecordingPlugin follower = new RecordingPlugin("follower", "follower-hook");
    StubLoader loader =
        new StubLoader(base, other, follower).requires(follower, "base").requires(follower, "other");
    DefaultPluginManager pm = new DefaultPluginManager(loader, reg);

    assertTrue(pm.disablePlugin("base"));
    assertTrue(pm.disablePlugin("other"));
    assertTrue(pm.enablePlugin("base"));

    assertFalse(
        pm.isPluginActive("follower"), "a plugin came back while one of its requirements was still down");

    assertTrue(pm.enablePlugin("other"));
    assertTrue(pm.isPluginActive("follower"));
  }

  /**
   * A plugin that requires a plugin of its own name is never suspended or resumed because of itself: the walk
   * skips the one whose name was just changed and moves on to the plugin that genuinely depends on it.
   */
  @Test
  void aPluginThatRequiresItsOwnNameIsNotSuspendedBecauseOfItself() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    RecordingPlugin base = new RecordingPlugin("base", "base-hook");
    RecordingPlugin follower = new RecordingPlugin("follower", "follower-hook");
    StubLoader loader =
        new StubLoader(base, follower).requires(base, "base").requires(follower, "base");
    DefaultPluginManager pm = new DefaultPluginManager(loader, reg);

    assertTrue(pm.disablePlugin("base"));
    assertFalse(pm.isPluginActive("follower"), "the depender of a self-requiring plugin stayed active");

    assertTrue(pm.enablePlugin("base"));
    assertTrue(pm.isPluginActive("follower"), "the depender did not come back");
  }

  /** A plugin that fails while it is being resumed stays suspended instead of coming back half-registered. */
  @Test
  void aPluginThatFailsToResumeStaysSuspended() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    RecordingPlugin base = new RecordingPlugin("base", "base-hook");
    class FailingPlugin implements WeaverPlugin {
      boolean fail;

      public String name() {
        return "follower";
      }

      public void registerInterceptors(InterceptorRegistry r) {
        if (fail) throw new IllegalStateException("cannot resume");
      }

      public void destroy() {}
    }
    FailingPlugin follower = new FailingPlugin();
    StubLoader loader = new StubLoader(base, follower).requires(follower, "base");
    DefaultPluginManager pm = new DefaultPluginManager(loader, reg);

    assertTrue(pm.disablePlugin("base"));
    follower.fail = true;
    assertTrue(pm.enablePlugin("base"));

    assertFalse(pm.isPluginActive("follower"), "a plugin that threw while resuming was marked active");
  }

  /**
   * Ownership guessed from a name has to refuse a name with nothing in it, and a name whose class part is not
   * separated from the plugin part. A field definition still belongs to the plugin once the kind word is
   * stepped over.
   */
  @Test
  void disablingAPluginJudgesOwnershipFromTheName() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    reg.register(named("audit-field-com.example.Order-total"));
    reg.register(named("com.example.Order-total"));
    RecordingPlugin audit = new RecordingPlugin("audit", "audit-hook");
    DefaultPluginManager pm = new DefaultPluginManager(new StubLoader(audit), reg);

    assertTrue(pm.disablePlugin("audit"));

    List<String> remaining = new ArrayList<>();
    for (InterceptorDefinition def : reg.getAllDefinitions()) {
      remaining.add(def.getName());
    }
    assertEquals(
        Collections.singletonList("com.example.Order-total"),
        remaining,
        "a definition with no plugin name in it was removed, or the field one was kept");
  }

  /**
   * A plugin that has already been unloaded cannot be disabled or enabled again, and the refusal is still
   * reported. When the event bus is down that report fails, which must not change the refusal.
   */
  @Test
  void aPluginThatCannotMoveStillReportsTheRefusal() throws Exception {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    RecordingPlugin plugin = new RecordingPlugin("stuck", "stuck-hook");
    DefaultPluginManager pm = new DefaultPluginManager(new StubLoader(plugin), reg);
    java.lang.reflect.Field entries = DefaultPluginManager.class.getDeclaredField("pluginEntries");
    java.util.Map<?, ?> tracked =
        (java.util.Map<?, ?>)
            com.github.cc11001100.weavergirl.core.testsupport.Statics.read(entries, pm);
    Object entry = tracked.values().iterator().next();
    java.lang.reflect.Field state = entry.getClass().getDeclaredField("state");
    state.setAccessible(true);
    state.set(entry, PluginState.UNLOADED);

    java.lang.reflect.Field current =
        com.github.cc11001100.weavergirl.api.tracing.Tracer.class.getDeclaredField("CURRENT_SPAN");
    Object real = com.github.cc11001100.weavergirl.core.testsupport.Statics.read(current, null);
    com.github.cc11001100.weavergirl.core.testsupport.Statics.write(current, null, spanThatFails());
    try {
      assertFalse(pm.disablePlugin("stuck"), "an unloaded plugin was disabled");
      assertFalse(pm.enablePlugin("stuck"), "an unloaded plugin was enabled");
    } finally {
      com.github.cc11001100.weavergirl.core.testsupport.Statics.write(current, null, real);
    }
  }

  /**
   * A plugin that throws while it is being enabled again is not enabled, and the failure is reported even
   * when the event bus itself is down.
   */
  @Test
  void aPluginThatFailsToEnableReportsTheFailure() throws Exception {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    class FailingPlugin implements WeaverPlugin {
      public String name() {
        return "fragile";
      }

      public void registerInterceptors(InterceptorRegistry r) {
        throw new IllegalStateException("cannot register");
      }

      public void destroy() {}
    }
    DefaultPluginManager pm = new DefaultPluginManager(new StubLoader(new FailingPlugin()), reg);
    assertTrue(pm.disablePlugin("fragile"));

    java.lang.reflect.Field current =
        com.github.cc11001100.weavergirl.api.tracing.Tracer.class.getDeclaredField("CURRENT_SPAN");
    Object real = com.github.cc11001100.weavergirl.core.testsupport.Statics.read(current, null);
    com.github.cc11001100.weavergirl.core.testsupport.Statics.write(current, null, spanThatFails());
    try {
      assertFalse(pm.enablePlugin("fragile"), "a plugin that threw while enabling was enabled");
    } finally {
      com.github.cc11001100.weavergirl.core.testsupport.Statics.write(current, null, real);
    }
    assertFalse(pm.isPluginActive("fragile"));
  }

  /** The current span holder, except reading it fails outright so a published event cannot be stamped. */
  private static ThreadLocal<Object> spanThatFails() {
    return new ThreadLocal<Object>() {
      @Override
      public Object get() {
        throw new OutOfMemoryError("span unavailable");
      }
    };
  }

  /** A plugin that is already disabled cannot be disabled again. */
  @Test
  void disablingAnAlreadyDisabledPluginIsRejected() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    RecordingPlugin plugin = new RecordingPlugin("once", "once-hook");
    DefaultPluginManager pm = new DefaultPluginManager(new StubLoader(plugin), reg);

    assertTrue(pm.disablePlugin("once"));
    assertFalse(pm.disablePlugin("once"), "a disabled plugin was disabled a second time");
  }

  @Test
  void unloadPlugin_doesNotRemoveInterceptorsOfAPluginWhoseNameExtendsIt() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    reg.register(named("jdbc-java.sql.Statement-execute"));
    reg.register(named("jdbc-pool-com.zaxxer.hikari.HikariDataSource-getConnection"));

    WeaverPlugin jdbc = namedPlugin("jdbc");
    WeaverPlugin pool = namedPlugin("jdbc-pool");
    StubLoader loader = new StubLoader(jdbc, pool);
    loader.ownership().record(jdbc, "jdbc-java.sql.Statement-execute");
    loader.ownership().record(pool, "jdbc-pool-com.zaxxer.hikari.HikariDataSource-getConnection");
    DefaultPluginManager pm = new DefaultPluginManager(loader, reg);

    assertTrue(pm.unloadPlugin("jdbc"));

    assertTrue(
        reg.getAllDefinitions().stream()
            .anyMatch(
                d -> d.getName().equals("jdbc-pool-com.zaxxer.hikari.HikariDataSource-getConnection")),
        "unloading jdbc also removed jdbc-pool's interceptor");
  }

  /**
   * A plugin that registered nothing leaves no ownership record, so its interceptors are recognised by name
   * alone. A name with no dot belongs to it only when it is exactly the plugin name, and a field interceptor
   * has its kind word stepped over before the names are compared.
   */
  @Test
  void disablePlugin_fallsBackToTheNameWhenNothingWasRecorded() {
    DefaultInterceptorRegistry reg = new DefaultInterceptorRegistry();
    reg.register(named("plain"));
    reg.register(named("plain-field-com.example.Type-amount"));
    reg.register(named("com.example.Type-amount"));
    reg.register(named("other-com.example.Type-amount"));

    DefaultPluginManager pm = new DefaultPluginManager(new StubLoader(namedPlugin("plain")), reg);

    assertTrue(pm.disablePlugin("plain"));

    List<String> remaining = new ArrayList<>();
    for (InterceptorDefinition def : reg.getAllDefinitions()) {
      remaining.add(def.getName());
    }
    assertEquals(
        Arrays.asList("com.example.Type-amount", "other-com.example.Type-amount"),
        remaining,
        "the name fallback removed interceptors that are not the plugin's");
  }

  /** A plugin whose only behaviour is its name. */
  private static WeaverPlugin namedPlugin(String pluginName) {
    return new WeaverPlugin() {
      public String name() {
        return pluginName;
      }

      public void registerInterceptors(InterceptorRegistry r) {}

      public void destroy() {}
    };
  }

  /**
   * A loader that holds the given plugins and its own ownership record. Tests register each plugin's
   * interceptors through {@code ownership().recording(name, registry)} before building the manager, which
   * is what the real loader does while a plugin loads.
   */
  private static final class StubLoader extends PluginLoader {
    private final List<WeaverPlugin> plugins;
    private final PluginOwnership record = new PluginOwnership();
    private final Map<WeaverPlugin, List<String>> required = new HashMap<>();

    StubLoader(WeaverPlugin... plugins) {
      this.plugins = Arrays.asList(plugins);
    }

    /** State that {@code plugin} cannot run without {@code requirement}. */
    StubLoader requires(WeaverPlugin plugin, String requirement) {
      required.computeIfAbsent(plugin, key -> new ArrayList<>()).add(requirement);
      return this;
    }

    @Override
    public List<String> requirementsOf(WeaverPlugin plugin) {
      List<String> names = required.get(plugin);
      return names == null ? Collections.emptyList() : names;
    }

    @Override
    public List<WeaverPlugin> getLoadedPlugins() {
      return plugins;
    }

    @Override
    public PluginOwnership ownership() {
      return record;
    }
  }

  private static InterceptorDefinition named(String name) {
    return new InterceptorDefinition(
        name, new Pointcut(ClassMatcher.byName("x"), MethodMatcher.any()), new Interceptor() {});
  }
}
