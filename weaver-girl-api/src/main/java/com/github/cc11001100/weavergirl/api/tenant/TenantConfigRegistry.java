package com.github.cc11001100.weavergirl.api.tenant;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for per-tenant configuration overrides.
 *
 * <p>Thread-safe registry that maps tenant IDs to their specific configuration. Plugins can query
 * this to apply per-tenant behavior.
 *
 * @since 1.1.0
 */
public final class TenantConfigRegistry {

  private static final ConcurrentHashMap<String, TenantConfig> registry = new ConcurrentHashMap<>();

  private TenantConfigRegistry() {}

  /**
   * Register a tenant configuration.
   *
   * @param config the tenant config to register
   */
  public static void register(TenantConfig config) {
    if (config != null && config.getTenantId() != null) {
      registry.put(config.getTenantId(), config);
    }
  }

  /**
   * Get the configuration for a specific tenant.
   *
   * @param tenantId the tenant ID
   * @return the tenant config, or null if not registered
   */
  public static TenantConfig get(String tenantId) {
    return tenantId != null ? registry.get(tenantId) : null;
  }

  /**
   * Get the configuration for the current thread's tenant.
   *
   * @return the tenant config, or null if no tenant context or not registered
   */
  public static TenantConfig getCurrent() {
    String tenantId = TenantContext.getTenantId();
    return tenantId != null ? registry.get(tenantId) : null;
  }

  /**
   * Remove a tenant configuration.
   *
   * @param tenantId the tenant ID to remove
   * @return the removed config, or null
   */
  public static TenantConfig unregister(String tenantId) {
    return tenantId != null ? registry.remove(tenantId) : null;
  }

  /**
   * Get all registered tenant IDs.
   *
   * @return unmodifiable set of tenant IDs
   */
  public static Set<String> getRegisteredTenantIds() {
    return Collections.unmodifiableSet(registry.keySet());
  }

  /** Clear all registered tenant configurations. */
  public static void clear() {
    registry.clear();
  }

  /** Get the number of registered tenants. */
  public static int size() {
    return registry.size();
  }
}
