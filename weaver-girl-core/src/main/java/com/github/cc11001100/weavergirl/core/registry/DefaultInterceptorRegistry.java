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
import com.github.cc11001100.weavergirl.core.event.LifecycleEvents;
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
  private void refreshPrecedence() {
    Map<String, Integer> newMap = new LinkedHashMap<>();
    for (InterceptorDefinition def : definitions) {
      String aspectName = def.getAspectClassName();
      if (aspectName == null || aspectName.isEmpty()) {
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
        log.debug("Failed to load aspect class {} for precedence resolution: {}", aspectName, t.getMessage());
      }
    }
    precedenceMap.clear();
    precedenceMap.putAll(newMap);
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

  /** Clear all registered definitions. For testing purposes. */
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
