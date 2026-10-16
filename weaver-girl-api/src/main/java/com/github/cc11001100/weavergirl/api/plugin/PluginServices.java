package com.github.cc11001100.weavergirl.api.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Services plugins offer each other.
 *
 * <p>A plugin that wants to be built on names a service by the interface it implements and registers
 * its implementation. Another plugin looks the interface up and gets every implementation currently
 * registered, in the order they were registered. Neither plugin names the other: the interface is the
 * whole contract between them, so a product can add, replace or remove a capability by adding or
 * removing a plugin.
 *
 * <p>The registry is process-wide and static, because the plugin that publishes and the plugin that
 * consumes are loaded by different class loaders and share nothing but {@code weaver-girl-api}. A
 * service type that is not part of that shared API is invisible to every plugin but the one that
 * defined it, so a service meant to be consumed has to be declared where both can see it.
 *
 * @since 1.0.0
 */
public final class PluginServices {

  private static final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Object>> SERVICES =
      new ConcurrentHashMap<>();

  private PluginServices() {}

  /**
   * Register {@code implementation} as a provider of {@code service}. A second registration of the same
   * instance is ignored, so a plugin that retries after a partial start does not publish itself twice.
   *
   * @param service the interface consumers look up
   * @param implementation an instance of {@code service}
   * @throws IllegalArgumentException when {@code implementation} does not implement {@code service}
   */
  public static void register(Class<?> service, Object implementation) {
    if (service == null || implementation == null) {
      throw new IllegalArgumentException("a service needs both a type and an implementation");
    }
    if (!service.isInstance(implementation)) {
      throw new IllegalArgumentException(
          implementation.getClass().getName() + " is not a " + service.getName());
    }
    CopyOnWriteArrayList<Object> providers =
        SERVICES.computeIfAbsent(service, key -> new CopyOnWriteArrayList<>());
    if (!providers.contains(implementation)) {
      providers.add(implementation);
    }
  }

  /**
   * The providers of {@code service} registered so far, in registration order. Empty when nobody has
   * registered one. The returned list is a snapshot: a provider registered or removed afterwards does not
   * change it.
   */
  public static <T> List<T> get(Class<T> service) {
    List<T> found = new ArrayList<>();
    if (service == null) {
      return found;
    }
    CopyOnWriteArrayList<Object> providers = SERVICES.get(service);
    if (providers != null) {
      for (Object provider : providers) {
        if (service.isInstance(provider)) {
          found.add(service.cast(provider));
        }
      }
    }
    return found;
  }

  /**
   * Remove every service {@code implementation} provides. A plugin calls this from {@code destroy()}, so a
   * capability it contributed disappears with it instead of being handed to consumers after it is gone.
   *
   * @return how many registrations were removed
   */
  public static int unregister(Object implementation) {
    if (implementation == null) {
      return 0;
    }
    int removed = 0;
    for (CopyOnWriteArrayList<Object> providers : SERVICES.values()) {
      if (providers.remove(implementation)) {
        removed++;
      }
    }
    return removed;
  }

  /** Remove every registered service. Tests use this so one test does not see another's services. */
  public static void clear() {
    SERVICES.clear();
  }
}
