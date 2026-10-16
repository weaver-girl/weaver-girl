package com.github.cc11001100.weavergirl.core.registry;

import com.github.cc11001100.weavergirl.api.ValidationUtils;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.api.security.SecurityPolicy;
import com.github.cc11001100.weavergirl.api.security.SecurityAuditLog;
import com.github.cc11001100.weavergirl.annotation.DeclarePrecedence;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.event.LifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default thread-safe implementation of InterceptorRegistry. Uses a ConcurrentHashMap index for
 * fast class-level lookup.
 */
public class DefaultInterceptorRegistry implements InterceptorRegistry {

  private static final Logger log = LoggerFactory.getLogger(DefaultInterceptorRegistry.class);

  private final List<InterceptorDefinition> definitions = new CopyOnWriteArrayList<>();
  private volatile Map<String, List<InterceptorDefinition>> classIndex = new ConcurrentHashMap<>();
  private volatile boolean indexDirty = true;
  private volatile long generation = 0;
  private final CopyOnWriteArrayList<Runnable> reloadHooks = new CopyOnWriteArrayList<>();
  private volatile SecurityPolicy securityPolicy;
  private final Map<String, Integer> precedenceMap = new ConcurrentHashMap<>();

  /** Return the current generation counter, incremented on every mutation. */
  public long getGeneration() {
    return generation;
  }

  public void setSecurityPolicy(SecurityPolicy policy) {
    this.securityPolicy = policy;
  }

  private SecurityPolicy getSecurityPolicy() {
    SecurityPolicy p = securityPolicy;
    return p != null ? p : SecurityPolicy.builder().defaultAllow(true).build();
  }

  /**
   * Register a hook to be invoked after any mutation (register/unregister/clear).
   * The hook runs inside the same lock as the mutation, so it must be fast and non-blocking.
   * Hooks are stored in a CopyOnWriteArrayList, so iteration is safe during invocation.
   */
  public void addReloadHook(Runnable hook) {
    if (hook != null) {
      reloadHooks.add(hook);
    }
  }

  /** Remove a previously registered reload hook. */
  public void removeReloadHook(Runnable hook) {
    reloadHooks.remove(hook);
  }

  /** Invoke all registered reload hooks. Must be called inside the synchronized block. */
  private void fireReloadHooks() {
    for (Runnable hook : reloadHooks) {
      try {
        hook.run();
      } catch (Throwable t) {
        log.warn("[DynamicRegistry] Reload hook failed: {}", t.getMessage());
      }
    }
  }

  /**
   * Refresh the precedence map by scanning all registered aspect classes for {@link DeclarePrecedence}
   * annotations. The precedence map assigns each aspect class a stable precedence index based on its
   * position in its own declaration.
   */
  /**
   * Aspect names that failed to resolve to a class. {@code getAspectClassName()} falls back to
   * the definition name, which is not a class, so every registration used to repeat a failing
   * {@code Class.forName} per definition — quadratic, and the dominant cost of the
   * concurrent-registration stress tests. Names that do resolve are not cached, so a precedence
   * annotation added later is still picked up.
   */
  private final Set<String> precedenceUnresolvable = new HashSet<>();

  private void refreshPrecedence() {
    Map<String, Integer> newMap = new LinkedHashMap<>();
    for (InterceptorDefinition def : definitions) {
      String aspectName = def.getAspectClassName();
      if (aspectName == null || aspectName.isEmpty() || precedenceUnresolvable.contains(aspectName)) {
        continue;
      }
      try {
        Class<?> aspectClass = Class.forName(aspectName, false, Thread.currentThread().getContextClassLoader());
        DeclarePrecedence declarePrecedence = aspectClass.getAnnotation(DeclarePrecedence.class);
        if (declarePrecedence != null) {
          String[] ordered = declarePrecedence.value().split(",");
          for (int i = 0; i < ordered.length; i++) {
            String name = ordered[i].trim();
            if (!name.isEmpty() && !newMap.containsKey(name)) {
              newMap.put(name, i);
            }
          }
        }
      } catch (Throwable t) {
        precedenceUnresolvable.add(aspectName);
        log.debug("Failed to load aspect class {} for precedence resolution: {}", aspectName, t.getMessage());
      }
    }
    precedenceMap.clear();
    precedenceMap.putAll(newMap);
  }

  /**
   * Who is registering right now, for the current thread. A plugin's {@code init} and {@code
   * registerInterceptors} run on one thread, and that is the only moment a registration can be tied to
   * the plugin making it. Set by the loader around both calls, so a plugin that registers through a
   * registry it was handed earlier — or through any other registry it can reach — is still credited.
   */
  private static final ThreadLocal<com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin>
      REGISTERING_PLUGIN = new ThreadLocal<>();

  /**
   * Run {@code action} with {@code plugin} recorded as the owner of every interceptor registered on
   * this thread while it runs.
   */
  public static void registeringAs(
      com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin plugin, Runnable action) {
    com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin previous = REGISTERING_PLUGIN.get();
    REGISTERING_PLUGIN.set(plugin);
    try {
      action.run();
    } finally {
      if (previous == null) {
        REGISTERING_PLUGIN.remove();
      } else {
        REGISTERING_PLUGIN.set(previous);
      }
    }
  }

  /** The plugin whose registration is in progress on this thread, or {@code null}. */
  public static com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin registeringPlugin() {
    return REGISTERING_PLUGIN.get();
  }

  /**
   * The loaded plugins, by the class loader that defined them. A plugin's own classes are defined by
   * its loader, so a registration whose call stack passes through one of those classes belongs to that
   * plugin — on any thread, not just the one that loaded it. One loader can define several plugins, and
   * each keeps its own entry: a registration is credited to the one whose own class is on the stack.
   */
  private static final java.util.concurrent.ConcurrentHashMap<
          ClassLoader, java.util.concurrent.CopyOnWriteArrayList<
              com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin>>
      PLUGINS_BY_LOADER = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Classes a plugin has declared as its own, beyond the ones its loader defined. A plugin that delegates
   * its registration to a helper class it does not own — a shared library, a class the application loaded —
   * names that class here, and a registration made through it is credited to the plugin. Naming the class
   * rather than its loader keeps every other class that loader defines, including another plugin's, out of
   * the claim. Several plugins may declare the same class; each keeps its own claim, and a registration is
   * credited to whichever of them is nearest the call.
   */
  private static final java.util.concurrent.ConcurrentHashMap<
          String, java.util.concurrent.CopyOnWriteArrayList<
              com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin>>
      DECLARED_CLASSES = new java.util.concurrent.ConcurrentHashMap<>();

  /** Remember that {@code plugin} was loaded, so later registrations from its classes can be credited. */
  public static void track(com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin plugin) {
    if (plugin != null && plugin.getClass().getClassLoader() != null) {
      java.util.concurrent.CopyOnWriteArrayList<com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin>
          plugins = PLUGINS_BY_LOADER.computeIfAbsent(
              plugin.getClass().getClassLoader(), loader -> new java.util.concurrent.CopyOnWriteArrayList<>());
      if (!plugins.contains(plugin)) {
        plugins.add(plugin);
      }
    }
  }

  /**
   * Credit registrations made through {@code helper} to {@code plugin}. A plugin calls this for a class it
   * delegates the registering to, so a registration that never passes through the plugin's own classes is
   * still its own. The class has to be named exactly: declaring the loader that defined it would also claim
   * every other class that loader defines. Another plugin may declare the same class; both claims are kept,
   * and the one nearest the registration wins. The loader that defined the plugin needs no declaration:
   * {@link #track} already covers its classes.
   */
  public static void track(
      com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin plugin, Class<?> helper) {
    if (plugin != null && helper != null) {
      java.util.concurrent.CopyOnWriteArrayList<com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin>
          claimants = DECLARED_CLASSES.computeIfAbsent(
              helper.getName(), name -> new java.util.concurrent.CopyOnWriteArrayList<>());
      if (!claimants.contains(plugin)) {
        claimants.add(plugin);
      }
    }
  }

  /**
   * Forget every tracked plugin and every declared loader. A plugin that was not unloaded — a test that
   * built its own loader and threw it away — would otherwise leave its declaration behind, and a later
   * registration on the same JVM would be credited to a plugin that no longer exists.
   */
  public static void clearTracking() {
    PLUGINS_BY_LOADER.clear();
    DECLARED_CLASSES.clear();
  }

  /** Forget {@code plugin}, because it was unloaded. */
  public static void untrack(com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin plugin) {
    if (plugin != null) {
      ClassLoader loader = plugin.getClass().getClassLoader();
      java.util.concurrent.CopyOnWriteArrayList<com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin>
          plugins = loader == null ? null : PLUGINS_BY_LOADER.get(loader);
      if (plugins != null) {
        plugins.remove(plugin);
      }
      for (java.util.concurrent.CopyOnWriteArrayList<
              com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin>
          claimants : DECLARED_CLASSES.values()) {
        claimants.remove(plugin);
      }
    }
  }

  /**
   * The plugin that owns the class on the call stack that called into the registry, or {@code null}
   * when the registration did not come from a loaded plugin's class.
   */
  private static com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin pluginOnCallStack() {
    if (PLUGINS_BY_LOADER.isEmpty() && DECLARED_CLASSES.isEmpty()) {
      return null;
    }
    // The frames are the classes really on the stack, not names resolved afterwards. Two plugins can
    // both define a class of the same name, and resolving that name through each loader would find both;
    // the class object says which one actually ran.
    List<Class<?>> stack = callerClasses();
    // Every frame is scored rather than the walk stopping at the first one, because a helper class both
    // plugins declared sits between them and the registration: the plugin nearest the call is the one
    // that made it.
    int best = Integer.MAX_VALUE;
    com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin nearest = null;
    for (int depth = 0; depth < stack.size(); depth++) {
      Class<?> frame = stack.get(depth);
      // A class the bootstrap loader defined has no ClassLoader, and the map rejects a null key.
      ClassLoader frameLoader = frame.getClassLoader();
      java.util.List<com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin> byLoader =
          frameLoader == null ? null : PLUGINS_BY_LOADER.get(frameLoader);
      // A child-first plugin loader resolves its parents' classes too, and reports itself as nowhere
      // near them. A class one of its ancestors defines belongs to that ancestor, not to the plugin.
      // The frame's class object says which of the loader's plugins ran. A frame that is a plugin's own
      // class is decisive even when another plugin shares the loader and the class name; any other class
      // the loader defines belongs to whichever of its plugins is nearest the call.
      if (byLoader != null && !definedByAncestor(frame.getName(), frame.getClassLoader())) {
        com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin owner = null;
        for (com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin plugin : byLoader) {
          if (frame == plugin.getClass()) {
            owner = plugin;
            break;
          }
          if (owner == null) {
            owner = plugin;
          }
        }
        if (owner != null && depth < best) {
          best = depth;
          nearest = owner;
        }
      }
      // A class the plugin declared rather than one its loader defined. Matched by the class object, so
      // declaring a helper claims that helper and nothing else the helper's loader happens to define.
      // When several plugins declared it, the one that appears nearest the call owns the registration.
      java.util.List<com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin> declared =
          DECLARED_CLASSES.get(frame.getName());
      if (declared != null) {
        for (com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin plugin : declared) {
          int pluginDepth = depthOf(stack, plugin);
          if (pluginDepth >= 0 && pluginDepth < best) {
            best = pluginDepth;
            nearest = plugin;
          }
        }
      }
    }
    return nearest;
  }

  /** The shallowest frame whose class is {@code plugin}'s own, or {@code -1} when it is not on the stack. */
  private static int depthOf(
      List<Class<?>> stack, com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin plugin) {
    Class<?> pluginClass = plugin.getClass();
    for (int depth = 0; depth < stack.size(); depth++) {
      if (stack.get(depth) == pluginClass) {
        return depth;
      }
    }
    return -1;
  }

  /**
   * Set by a test to pretend {@link CallStack} cannot be loaded, which is what a Java 8 runtime does. The
   * class context below is then the only way to get the class object of a frame.
   */
  public static volatile boolean ignoreCallStack = false;

  /**
   * The classes on the current call stack, shallowest first. On Java 9 and newer {@link CallStack} returns the
   * class object of each frame, which is the only way to tell apart two classes that share a name. It is
   * compiled separately and shipped under {@code META-INF/versions/9}, so a Java 8 runtime never loads it. A
   * Java 8 runtime still has {@link ClassContext}, which returns the same class objects, and only a runtime
   * where that also fails falls back to the frame names; a stack trace only carries the name, and the first
   * plugin loader that defined a class of that name stands for the frame.
   */
  private static List<Class<?>> callerClasses() {
    if (!ignoreCallStack) {
      List<Class<?>> frames = invokeClasses("com.github.cc11001100.weavergirl.core.registry.CallStack");
      if (frames != null) {
        return frames;
      }
    }
    List<Class<?>> frames = invokeClasses("com.github.cc11001100.weavergirl.core.registry.ClassContext");
    if (frames != null) {
      return frames;
    }
    return classesNamedOnTheStack();
  }

  /** {@code classes()} of {@code name}, or {@code null} when that class cannot be loaded or run. */
  @SuppressWarnings("unchecked")
  private static List<Class<?>> invokeClasses(String name) {
    try {
      Class<?> type = Class.forName(name);
      java.lang.reflect.Method classes = type.getDeclaredMethod("classes");
      classes.setAccessible(true);
      return (List<Class<?>>) classes.invoke(null);
    } catch (ReflectiveOperationException | UnsupportedClassVersionError ignored) {
      return null;
    }
  }

  /** Java 8 fallback: the name is all a frame carries, so the class is resolved through the plugin loaders. */
  private static List<Class<?>> classesNamedOnTheStack() {
    List<Class<?>> found = new ArrayList<>();
    for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
      Class<?> resolved = resolveFrame(frame.getClassName());
      if (resolved != null) {
        found.add(resolved);
      }
    }
    return found;
  }

  /** The class {@code name} names, preferring one a plugin loader defined over one an ancestor defines. */
  private static Class<?> resolveFrame(String name) {
    Class<?> shared = null;
    for (ClassLoader loader : new java.util.ArrayList<>(PLUGINS_BY_LOADER.keySet())) {
      try {
        Class<?> resolved = Class.forName(name, false, loader);
        if (resolved.getClassLoader() == loader && !definedByAncestor(name, loader)) {
          return resolved;
        }
        if (shared == null) {
          shared = resolved;
        }
      } catch (ClassNotFoundException ignored) {
        // This loader cannot see the frame's class.
      }
    }
    if (shared != null) {
      return shared;
    }
    try {
      return Class.forName(name, false, DefaultInterceptorRegistry.class.getClassLoader());
    } catch (ClassNotFoundException ignored) {
      return null;
    }
  }

  /**
   * Whether some ancestor of {@code loader} defines {@code className}. A loader a plugin declares is
   * usually the parent of its own loader, and every class that parent already defines — the agent's, the
   * application's — would otherwise be credited to the plugin. Only a class that enters through the
   * declared loader and no earlier counts.
   */
  private static boolean definedByAncestor(String className, ClassLoader loader) {
    if (loader == null) {
      return false;
    }
    for (ClassLoader ancestor = loader.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
      try {
        Class<?> resolved = Class.forName(className, false, ancestor);
        if (resolved.getClassLoader() == ancestor) {
          return true;
        }
      } catch (ClassNotFoundException ignored) {
        // This ancestor cannot see the class.
      }
    }
    try {
      // The bootstrap loader is the end of every parent chain and has no ClassLoader of its own.
      Class.forName(className, false, null);
      return true;
    } catch (ClassNotFoundException ignored) {
      return false;
    }
  }



  @Override
  public void register(InterceptorDefinition definition) {
    if (definition == null) {
      log.warn(
          "Attempted to register null InterceptorDefinition, ignoring. Call stack:",
          new Exception());
      return;
    }

    // Security policy enforcement
    SecurityPolicy policy = getSecurityPolicy();
    if (policy != null) {
      String targetClass = definition.getPointcut().getClassMatcher().getPattern();
      if (!policy.isClassAllowed(targetClass)) {
        log.warn("SecurityPolicy denied registration of interceptor {} for class {}", definition.getName(), targetClass);
        SecurityAuditLog.recordInterception(definition.getName(), targetClass, false);
        return;
      }
    }

    // Whoever is loading a plugin on this thread owns the definition, even when the plugin registered
    // it through a registry it reached on its own rather than the one it was handed.
    if (definition.getOwner() == null) {
      com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin owner = REGISTERING_PLUGIN.get();
      if (owner == null) {
        // A plugin can register long after it loaded, and from a thread it started itself, where the
        // thread-local above is empty. The frame that called register still belongs to a class the
        // plugin defined, and that class says which plugin it was.
        owner = pluginOnCallStack();
      }
      if (owner != null) {
        definition.setOwner(owner);
      }
    }

    synchronized (this) {
      definitions.removeIf(d -> d.getName().equals(definition.getName()));
      definitions.add(definition);
      indexDirty = true;
      bumpGeneration();
      fireReloadHooks();
      // Refresh precedence ordering after each successful registration
      refreshPrecedence();
      // Invoke lifecycle hook
      boolean initOk = true;
      try {
        definition.getInterceptor().initialize();
      } catch (Throwable t) {
        initOk = false;
        log.warn("Interceptor {} initialize() failed: {}", definition.getName(), t.getMessage());
      }
      try {
        InterceptorEventPublisher.getInstance()
            .publish(
                LifecycleEvents.registry(
                    LifecycleEvents.PHASE_REGISTER, definition.getName(), initOk,
                    "targetClass=" + definition.getPointcut().getClassMatcher().getPattern()
                        + ";success=" + initOk));
      } catch (Throwable t) {
        log.debug("Lifecycle event publish failed: {}", t.getMessage());
      }
    }
    log.info("Registered interceptor: {}", definition.getName());
  }

  @Override
  public List<InterceptorDefinition> getInterceptorsForClass(String className) {
    ValidationUtils.requireNonEmpty(className, "className");
    if (indexDirty) {
      rebuildIndex();
    }
    List<InterceptorDefinition> result =
        new ArrayList<>(classIndex.getOrDefault(className, Collections.emptyList()));

    // Also check non-EXACT_NAME matchers (pattern, annotation, super, interface)
    // These can't be indexed by class name, so we scan all definitions
    for (InterceptorDefinition def : definitions) {
      ClassMatcher classMatcher = def.getPointcut().getClassMatcher();
      if (classMatcher.getMatchType() != ClassMatcher.MatchType.EXACT_NAME) {
        if (classMatcher.matches(className)) {
          if (!result.contains(def)) {
            result.add(def);
          }
        } else if (matchesByReflection(classMatcher, className)) {
          // INTERFACE/SUPER_CLASS/ANNOTATION matchers can't be decided by
          // ClassMatcher.matches(String) (it returns false for those types).
          // Fall back to a reflective runtime check so that dynamic-attach
          // retransformation can select already-loaded implementors
          // (e.g. ThreadPoolExecutor implementing ExecutorService). At
          // premain time classes are matched by ByteBuddy's type matcher
          // during load, so this path only matters for retransform.
          if (!result.contains(def)) {
            result.add(def);
          }
        }
      }
    }

    // Sort by priority first, then apply @DeclarePrecedence ordering
    result.sort(Comparator.comparingInt(InterceptorDefinition::getPriority));
    if (!precedenceMap.isEmpty()) {
      result.sort((a, b) -> {
        Integer pA = precedenceMap.get(a.getAspectClassName());
        Integer pB = precedenceMap.get(b.getAspectClassName());
        int cmp = Integer.compare(pA != null ? pA : Integer.MAX_VALUE, pB != null ? pB : Integer.MAX_VALUE);
        if (cmp != 0) {
          return cmp;
        }
        return Integer.compare(a.getPriority(), b.getPriority());
      });
    }
    return result;
  }

  @Override
  public List<InterceptorDefinition> getAllDefinitions() {
    return Collections.unmodifiableList(definitions);
  }

  @Override
  public boolean unregister(String name) {
    ValidationUtils.requireNonEmpty(name, "name");
    // Find the definition before removal so we can call destroy()
    InterceptorDefinition toRemove = null;
    for (InterceptorDefinition def : definitions) {
      if (name.equals(def.getName())) {
        toRemove = def;
        break;
      }
    }
    boolean removed = definitions.removeIf(d -> name.equals(d.getName()));
    if (removed) {
      indexDirty = true;
      bumpGeneration();
      fireReloadHooks();
      refreshPrecedence();
      log.info("Unregistered interceptor: {}", name);
      boolean destroyOk = true;
      if (toRemove != null) {
        try {
          toRemove.getInterceptor().destroy();
        } catch (Throwable t) {
          destroyOk = false;
          log.warn("Interceptor {} destroy() failed: {}", name, t.getMessage());
        }
      }
      try {
        InterceptorEventPublisher.getInstance()
            .publish(LifecycleEvents.registry(LifecycleEvents.PHASE_UNREGISTER, name, destroyOk, null));
      } catch (Throwable t) {
        log.debug("Lifecycle event publish failed: {}", t.getMessage());
      }
    }
    return removed;
  }

  /**
   * Remove every registered definition and reset the registry to its empty state. Part of the
   * unweave/uninstall path: wiped before the transformer is reset so the advice inlined into
   * still-woven classes finds nothing while the JVM restores their original bytecode.
   */
  @Override
  public void clear() {
    List<InterceptorDefinition> clearedDefinitions;
    synchronized (this) {
      clearedDefinitions = new ArrayList<>(definitions);
      definitions.clear();
      classIndex = new ConcurrentHashMap<>();
      indexDirty = true;
      bumpGeneration();
      fireReloadHooks();
    }
    precedenceMap.clear();
    precedenceUnresolvable.clear();
    for (InterceptorDefinition def : clearedDefinitions) {
      boolean destroyOk = true;
      try {
        def.getInterceptor().destroy();
      } catch (Throwable t) {
        destroyOk = false;
        log.warn("Interceptor {} destroy() failed during clear(): {}", def.getName(), t.getMessage());
      }
      try {
        InterceptorEventPublisher.getInstance()
            .publish(
                LifecycleEvents.registry(LifecycleEvents.PHASE_CLEAR, def.getName(), destroyOk, null));
      } catch (Throwable t) {
        log.debug("Lifecycle event publish failed: {}", t.getMessage());
      }
    }
  }

  /**
   * Reflective runtime check for match types that {@link ClassMatcher#matches(String)} cannot
   * decide (INTERFACE, SUPER_CLASS, ANNOTATION). Used by {@link #getInterceptorsForClass(String)}
   * so that dynamic-attach retransformation can select already-loaded implementors/subtypes/annotated classes.
   *
   * <p>Loads the candidate class via {@code Class.forName} (no initialization). Since this is only
   * consulted for classes reported by {@code Instrumentation.getAllLoadedClasses()} during
   * retransformation, the class is already loaded and this is a no-op lookup.
   *
   * <p>Public so {@code InterceptorHolder}'s snapshot cache can reuse the reflective runtime check
   * for INTERFACE/SUPER_CLASS/ANNOTATION matchers without duplicating logic.
   *
   * @param classMatcher the matcher with a non-name match type
   * @param className the fully-qualified class name to test
   * @return true if the loaded class satisfies the matcher, false otherwise (including when the
   *     class cannot be loaded)
   */
  public boolean matchesByReflection(ClassMatcher classMatcher, String className) {
    Class<?> candidate;
    try {
      candidate = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
    } catch (Throwable t) {
      // ClassNotFoundException, NoClassDefFoundError, SecurityException, etc.
      // — common for synthetic/hidden JVM-internal classes encountered during
      // retransformation scanning; treat as non-matching.
      return false;
    }
    switch (classMatcher.getMatchType()) {
      case INTERFACE:
        {
          Class<?> iface;
          try {
            iface =
                Class.forName(
                    classMatcher.getPattern(),
                    false,
                    Thread.currentThread().getContextClassLoader());
          } catch (Throwable t) {
            return false;
          }
          return iface.isInterface() && iface.isAssignableFrom(candidate);
        }
      case SUPER_CLASS:
        {
          Class<?> sup;
          try {
            sup =
                Class.forName(
                    classMatcher.getPattern(),
                    false,
                    Thread.currentThread().getContextClassLoader());
          } catch (Throwable t) {
            return false;
          }
          return sup.isAssignableFrom(candidate) && !sup.equals(candidate);
        }
      case ANNOTATION:
        // Annotation presence requires resolving the annotation type and reflecting
        // over the candidate; keep this path cheap by checking only direct/inheritable
        // annotations via the JVM-cached annotation array.
        Class<?> annoType;
        try {
          annoType =
              Class.forName(
                  classMatcher.getPattern(), false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable t) {
          return false;
        }
        if (!annoType.isAnnotation()) {
          return false;
        }
        @SuppressWarnings("unchecked")
        Class<? extends java.lang.annotation.Annotation> annoClass =
            (Class<? extends java.lang.annotation.Annotation>) annoType;
        return candidate.isAnnotationPresent(annoClass);
      default:
        return false;
    }
  }

  private synchronized void rebuildIndex() {
    if (!indexDirty) {
      return;
    }
    Map<String, List<InterceptorDefinition>> newIndex = new ConcurrentHashMap<>();
    for (InterceptorDefinition def : definitions) {
      String pattern = def.getPointcut().getClassMatcher().getPattern();
      newIndex.computeIfAbsent(pattern, k -> new CopyOnWriteArrayList<>()).add(def);
    }
    // Sort each list by priority first, then apply @DeclarePrecedence ordering
    for (List<InterceptorDefinition> list : newIndex.values()) {
      list.sort(Comparator.comparingInt(InterceptorDefinition::getPriority));
      if (!precedenceMap.isEmpty()) {
        list.sort((a, b) -> {
          Integer pA = precedenceMap.get(a.getAspectClassName());
          Integer pB = precedenceMap.get(b.getAspectClassName());
          int cmp = Integer.compare(pA != null ? pA : Integer.MAX_VALUE, pB != null ? pB : Integer.MAX_VALUE);
          if (cmp != 0) {
            return cmp;
          }
          return Integer.compare(a.getPriority(), b.getPriority());
        });
      }
    }
    classIndex = newIndex; // atomic swap
    indexDirty = false;
  }

  private void bumpGeneration() {
    generation++;
  }
}
