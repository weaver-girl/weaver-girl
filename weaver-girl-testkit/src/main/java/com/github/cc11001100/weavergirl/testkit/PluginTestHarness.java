package com.github.cc11001100.weavergirl.testkit;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.plugin.DefaultPluginContext;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.sampling.SamplingController;
import com.github.cc11001100.weavergirl.core.switches.GlobalInterceptionSwitch;
import com.github.cc11001100.weavergirl.core.transformer.WeaverTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a plugin against real classes in the test JVM, without starting the agent.
 *
 * <p>A plugin is normally exercised by putting its jar in {@code plugins/} and launching an
 * application. That is the right way to test packaging and class-loader isolation, but it is far too
 * slow for the question a unit test usually asks: <em>does this callback fire, and what does it do
 * to the call?</em> This harness attaches Byte Buddy's instrumentation agent to the running JVM,
 * installs the plugin through the same lifecycle the agent uses, and weaves exactly the classes the
 * test names.
 *
 * <p>Typical use:
 *
 * <pre>
 * &#64;Test
 * void chargeIsAudited() {
 *     try (PluginTestHarness harness = PluginTestHarness.create()
 *             .config("payment-audit", "enabled", "true")
 *             .install(new AuditPlugin())) {
 *         harness.weave(PaymentService.class);
 *
 *         new PaymentService().charge("4111111111111111", 250L);
 *
 *         assertEquals(250L, MetricRegistry.snapshot("audit.charged.cents").longValue());
 *     }
 * }</pre>
 *
 * <p>Lifecycle and configuration are the ones the agent applies: {@link WeaverPlugin#init} runs
 * first, then {@link WeaverPlugin#isEnabled}, then {@link
 * WeaverPlugin#registerInterceptors(InterceptorRegistry)}. A plugin whose {@link
 * WeaverPlugin#instrumentedLibraries()} are absent is left inactive, exactly as it would be at
 * runtime; {@link #isActive(String)} reports which happened.
 *
 * <p>The harness is not a substitute for the agent. It does not use a {@code PluginClassLoader}, so
 * it cannot prove that a plugin only touches classes it is allowed to see, and it does not load the
 * jar from a directory. One harness owns the JVM's interceptor registry while it is open; do not
 * open two at once, and prefer one harness per test so a failure cannot leak definitions into the
 * next one.
 *
 * <p>Closing the harness destroys the plugins (newest first), withdraws the services they
 * published, restores every class it wove, and puts the JVM's global interception state back the way
 * it was. Calling {@link #close()} twice is safe.
 *
 * <p>Every instance is created on the test's class path, so a plugin that reads the plugin contract
 * from {@code weaver-girl-api} sees the same classes the engine uses. Nothing here is shipped with a
 * product plugin: declare the dependency with {@code test} scope.
 *
 * @since 2.2.0
 */
public final class PluginTestHarness implements AutoCloseable {

  private final Instrumentation instrumentation;

  /**
   * The registry the plugins register into. Kept concrete because the harness reads definitions
   * back for {@link #wovenClasses()} diagnostics, and because the transformer's snapshot cache is
   * keyed on this implementation.
   */
  private final DefaultInterceptorRegistry registry;

  private final Map<String, String> config = new LinkedHashMap<String, String>();
  private final List<InstalledPlugin> plugins = new ArrayList<InstalledPlugin>();
  private final Map<String, Boolean> active = new LinkedHashMap<String, Boolean>();

  private final InterceptorRegistry previousRegistry;
  private final boolean previousInterceptionEnabled;
  private final int previousSamplingRate;

  private WeaverTransformer transformer;
  private boolean closed;

  private PluginTestHarness(Instrumentation instrumentation) {
    this.instrumentation = instrumentation;
    this.registry = new DefaultInterceptorRegistry();

    this.previousRegistry = InterceptorHolder.getRegistry();
    this.previousInterceptionEnabled = GlobalInterceptionSwitch.isEnabled();
    this.previousSamplingRate = SamplingController.getInstance().getSamplingRate();

    InterceptorHolder.setRegistry(registry);
    GlobalInterceptionSwitch.setEnabled(true, "testkit");
    SamplingController.getInstance().setSamplingRate(1);
    SamplingController.getInstance().resetCounter();
  }

  /**
   * Attach the instrumentation agent and create an empty harness.
   *
   * <p>Attaching is JVM-wide and cannot be undone; a second call reuses the already attached agent.
   * The method throws when the JVM refuses attachment (for example when self-attachment is disabled
   * by a security manager or the process was started with {@code -XX:-EnableDynamicAgentLoading} on
   * Java 21+). Callers that want to skip in that environment can catch the exception and use a JUnit
   * assumption instead.
   *
   * @return a new harness, whose global state is restored by {@link #close()}
   * @throws IllegalStateException when the instrumentation agent cannot be attached
   */
  public static PluginTestHarness create() {
    Instrumentation instrumentation;
    try {
      instrumentation = net.bytebuddy.agent.ByteBuddyAgent.install();
    } catch (Throwable failure) {
      throw new IllegalStateException(
          "Cannot attach the instrumentation agent; this JVM does not allow self-attachment", failure);
    }
    if (instrumentation == null) {
      throw new IllegalStateException("Cannot attach the instrumentation agent: install() returned null");
    }
    return new PluginTestHarness(instrumentation);
  }

  /**
   * Create a harness and install the given plugins, in order.
   *
   * @param plugins the plugins to install
   * @return a new harness, already holding the registered definitions
   */
  public static PluginTestHarness create(WeaverPlugin... plugins) {
    PluginTestHarness harness = create();
    try {
      for (WeaverPlugin plugin : plugins) {
        harness.install(plugin);
      }
    } catch (RuntimeException failure) {
      harness.close();
      throw failure;
    }
    return harness;
  }

  /**
   * Set a launch-wide configuration value, readable by every plugin.
   *
   * <p>This is the equivalent of an agent argument or a top-level YAML key. Call it before {@link
   * #install(WeaverPlugin)} for values the plugin reads in {@code init}. System properties still win
   * over these values, because the harness uses the agent's own {@link
   * com.github.cc11001100.weavergirl.core.plugin.DefaultPluginContext}.
   *
   * @param key the configuration key
   * @param value the value
   * @return this harness for chaining
   */
  public PluginTestHarness config(String key, String value) {
    ensureOpen();
    config.put(key, value);
    return this;
  }

  /**
   * Set a configuration value scoped to one plugin, as {@code plugins.<name>.<key>} in YAML.
   *
   * <p>Only the named plugin's {@code getConfig(key)} sees it; another plugin reading the same key
   * gets its own value or the default.
   *
   * @param pluginName the value returned by {@link WeaverPlugin#name()}
   * @param key the configuration key
   * @param value the value
   * @return this harness for chaining
   */
  public PluginTestHarness config(String pluginName, String key, String value) {
    return config("plugins." + pluginName + "." + key, value);
  }

  /**
   * Run a plugin through the initialization and registration steps of the agent lifecycle.
   *
   * <p>Call this before {@link #weave}. The transformer is built from the definitions that
   * are present when the first class is woven, which is why a plugin installed afterwards cannot
   * take effect and is rejected.
   *
   * @param plugin the plugin to install
   * @return this harness for chaining
   * @throws IllegalStateException when a class has already been woven
   */
  public PluginTestHarness install(WeaverPlugin plugin) {
    ensureOpen();
    if (plugin == null) {
      throw new NullPointerException("plugin");
    }
    if (transformer != null) {
      throw new IllegalStateException(
          "Cannot install " + plugin.name() + " after weave() has run: the transformer is already "
              + "built from the definitions registered at that point. Install every plugin first.");
    }
    DefaultPluginContext context = new DefaultPluginContext(registry, config, plugin.name());
    plugin.init(context);

    boolean isActive = plugin.isEnabled(context) && librariesPresent(plugin, context);
    active.put(plugin.name(), Boolean.valueOf(isActive));
    if (isActive) {
      plugin.registerInterceptors(registry);
    }
    plugins.add(new InstalledPlugin(plugin, context));
    return this;
  }

  /**
   * Install the harness's transformer and retransform the given already-loaded classes.
   *
   * <p>Each class must match a registered class matcher <em>and</em> declare at least one method the
   * definition selects; a class that matches neither fails the call rather than silently running
   * un-woven code. Callers that deliberately want to weave a class that has no matching join point
   * should use {@link #retransform} instead.
   *
   * @param classes the classes to weave
   * @return this harness for chaining
   * @throws IllegalStateException when a class was not woven, with the classes that were
   */
  public PluginTestHarness weave(Class<?>... classes) {
    retransform(classes);
    List<String> missed = new ArrayList<String>();
    Set<String> woven = transformer.getWovenClasses();
    for (Class<?> type : classes) {
      if (!woven.contains(type.getName())) {
        missed.add(type.getName());
      }
    }
    if (!missed.isEmpty()) {
      throw new IllegalStateException(
          "No interceptor matched "
              + missed
              + ". Registered definitions: "
              + definitionNames()
              + ". Install the plugin before weave(), and check the class/method matcher.");
    }
    return this;
  }

  /**
   * Install the transformer (once) and retransform the given classes without asserting that any
   * definition matched them.
   *
   * @param classes the classes to visit
   * @return this harness for chaining
   */
  public PluginTestHarness retransform(Class<?>... classes) {
    ensureOpen();
    ensureTransformer();
    try {
      instrumentation.retransformClasses(classes);
    } catch (java.lang.instrument.UnmodifiableClassException e) {
      throw new IllegalStateException("The JVM refuses to retransform one of the given classes", e);
    }
    return this;
  }

  /**
   * The names of the classes this harness has woven so far.
   *
   * @return an immutable snapshot, empty before the first {@link #weave} or {@link
   *     #retransform(Class[])}
   */
  public Set<String> wovenClasses() {
    return transformer == null ? Collections.<String>emptySet() : transformer.getWovenClasses();
  }

  /**
   * Whether the plugin was activated. A plugin that is disabled by configuration, or whose
   * instrumented library is absent, is installed but inactive and registers no interceptors.
   *
   * @param pluginName the value returned by {@link WeaverPlugin#name()}
   * @return true when the plugin's interceptors were registered
   */
  public boolean isActive(String pluginName) {
    Boolean value = active.get(pluginName);
    if (value == null) {
      throw new IllegalArgumentException("No plugin named '" + pluginName + "' was installed");
    }
    return value.booleanValue();
  }

  /**
   * The context the named plugin was initialized with, for assertions about configuration or for
   * registering a service a test wants to look up.
   *
   * @param pluginName the value returned by {@link WeaverPlugin#name()}
   * @return the context, never null
   */
  public PluginContext context(String pluginName) {
    for (InstalledPlugin installed : plugins) {
      if (installed.plugin.name().equals(pluginName)) {
        return installed.context;
      }
    }
    throw new IllegalArgumentException("No plugin named '" + pluginName + "' was installed");
  }

  /**
   * The registry the plugins registered into.
   *
   * <p>Use it to register a definition directly, or to assert what a plugin registered:
   *
   * <pre>
   * harness.registry().register(definition);
   * assertEquals(1, harness.registry().getInterceptorsForClass(Target.class.getName()).size());
   * </pre>
   *
   * @return the harness's registry, never null
   */
  public InterceptorRegistry registry() {
    return registry;
  }

  /**
   * Destroy the installed plugins, restore every woven class, and put the JVM's global interception
   * state back. Safe to call more than once.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;

    Throwable failure = null;
    for (int i = plugins.size() - 1; i >= 0; i--) {
      InstalledPlugin installed = plugins.get(i);
      try {
        installed.plugin.destroy();
      } catch (Throwable t) {
        failure = append(failure, t);
      }
      try {
        installed.context.withdrawServices();
      } catch (Throwable t) {
        failure = append(failure, t);
      }
    }

    try {
      if (transformer != null) {
        transformer.unweave();
      }
    } catch (Throwable t) {
      failure = append(failure, t);
    } finally {
      InterceptorHolder.setRegistry(previousRegistry);
      SamplingController.getInstance().setSamplingRate(previousSamplingRate);
      SamplingController.getInstance().resetCounter();
      GlobalInterceptionSwitch.setEnabled(previousInterceptionEnabled, "testkit");
    }

    if (failure != null) {
      throw new IllegalStateException("A plugin or the transformer failed while closing the harness", failure);
    }
  }

  private void ensureTransformer() {
    if (transformer != null) {
      return;
    }
    WeaverTransformer installed = new WeaverTransformer(registry);
    // No eager retransformation: the test names the classes it wants woven, so the harness does not
    // walk every loaded class in the JVM (and does not weave test or framework classes by accident).
    installed.install(instrumentation, false);
    this.transformer = installed;
  }

  private boolean librariesPresent(WeaverPlugin plugin, PluginContext context) {
    String[] libraries = plugin.instrumentedLibraries();
    if (libraries == null || libraries.length == 0) {
      return true;
    }
    for (String library : libraries) {
      // The runtime asks the plugin loader; in-process there is no separate plugin loader, so the
      // test's context loader is the closest equivalent.
      if (context.classPresent(Thread.currentThread().getContextClassLoader(), library)) {
        return true;
      }
    }
    return false;
  }

  private List<String> definitionNames() {
    List<String> names = new ArrayList<String>();
    for (com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition definition :
        registry.getAllDefinitions()) {
      names.add(definition.getName());
    }
    return names;
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("This PluginTestHarness is already closed");
    }
  }

  private static Throwable append(Throwable current, Throwable next) {
    if (current == null) {
      return next;
    }
    current.addSuppressed(next);
    return current;
  }

  private static final class InstalledPlugin {
    final WeaverPlugin plugin;
    final DefaultPluginContext context;

    InstalledPlugin(WeaverPlugin plugin, DefaultPluginContext context) {
      this.plugin = plugin;
      this.context = context;
    }
  }
}
