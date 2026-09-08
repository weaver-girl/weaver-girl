package com.github.cc11001100.weavergirl.api.tenant;

/**
 * Immutable snapshot of tenant context for cross-thread propagation.
 *
 * @since 1.1.0
 */
public final class TenantSnapshot {

  private final String tenantId;
  private final String tenantGroup;

  /**
   * Create a tenant snapshot.
   *
   * @param tenantId the tenant identifier (may be null)
   * @param tenantGroup the tenant group (may be null)
   */
  public TenantSnapshot(String tenantId, String tenantGroup) {
    this.tenantId = tenantId;
    this.tenantGroup = tenantGroup;
  }

  /** The tenant identifier. */
  public String getTenantId() {
    return tenantId;
  }

  /** The tenant group. */
  public String getTenantGroup() {
    return tenantGroup;
  }

  /** Whether this snapshot has a tenant ID set. */
  public boolean hasTenant() {
    return tenantId != null;
  }

  @Override
  public String toString() {
    return "TenantSnapshot{tenantId='" + tenantId + "', group='" + tenantGroup + "'}";
  }
}
