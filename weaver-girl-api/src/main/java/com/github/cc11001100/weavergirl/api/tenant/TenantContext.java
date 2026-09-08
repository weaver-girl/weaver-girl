package com.github.cc11001100.weavergirl.api.tenant;

/**
 * Holds the current tenant identifier for multi-tenant isolation.
 *
 * <p>Uses a ThreadLocal to propagate tenant context across the call stack. Plugins can check the
 * current tenant to apply per-tenant configuration, sampling rates, and event filtering.
 *
 * <h3>Usage:</h3>
 *
 * <pre>
 * // Set tenant for the current request
 * TenantContext.setTenantId("customer-123");
 * try {
 *     // All interceptors see this tenant
 *     doWork();
 * } finally {
 *     TenantContext.clear();
 * }
 *
 * // Read tenant in interceptor
 * public void before(MethodInvocation invocation) {
 *     String tenant = TenantContext.getTenantId();
 *     if (tenant != null) {
 *         // Apply per-tenant logic
 *     }
 * }
 * </pre>
 *
 * @since 1.1.0
 */
public final class TenantContext {

  private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
  private static final ThreadLocal<String> TENANT_GROUP = new ThreadLocal<>();

  private TenantContext() {}

  /**
   * Set the tenant ID for the current thread.
   *
   * @param tenantId the tenant identifier
   */
  public static void setTenantId(String tenantId) {
    TENANT_ID.set(tenantId);
  }

  /**
   * Get the tenant ID for the current thread.
   *
   * @return the tenant ID, or null if not set
   */
  public static String getTenantId() {
    return TENANT_ID.get();
  }

  /**
   * Set the tenant group for organizational grouping.
   *
   * @param group the tenant group name
   */
  public static void setTenantGroup(String group) {
    TENANT_GROUP.set(group);
  }

  /**
   * Get the tenant group.
   *
   * @return the tenant group, or null if not set
   */
  public static String getTenantGroup() {
    return TENANT_GROUP.get();
  }

  /**
   * Check if a tenant context is set.
   *
   * @return true if a tenant ID is set
   */
  public static boolean isSet() {
    return TENANT_ID.get() != null;
  }

  /**
   * Clear all tenant context for the current thread. Must be called in finally blocks to prevent
   * ThreadLocal leaks.
   */
  public static void clear() {
    TENANT_ID.remove();
    TENANT_GROUP.remove();
  }

  /**
   * Capture the current tenant context for cross-thread propagation.
   *
   * @return a snapshot of the current tenant state
   */
  public static TenantSnapshot capture() {
    return new TenantSnapshot(TENANT_ID.get(), TENANT_GROUP.get());
  }

  /**
   * Restore a previously captured tenant context.
   *
   * @param snapshot the snapshot to restore
   */
  public static void restore(TenantSnapshot snapshot) {
    if (snapshot != null) {
      TENANT_ID.set(snapshot.getTenantId());
      TENANT_GROUP.set(snapshot.getTenantGroup());
    }
  }
}
