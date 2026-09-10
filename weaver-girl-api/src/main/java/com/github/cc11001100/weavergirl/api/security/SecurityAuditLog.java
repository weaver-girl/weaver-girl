package com.github.cc11001100.weavergirl.api.security;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.event.LifecycleEvents;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe audit log for security-relevant operations.
 *
 * @since 1.1.0
 */
public final class SecurityAuditLog {

  private static final int MAX_RECORDS = 500;
  private static final CopyOnWriteArrayList<AuditRecord> records = new CopyOnWriteArrayList<>();
  private static volatile SecurityPolicy policy = SecurityPolicy.builder().build();

  private SecurityAuditLog() {}

  /**
   * Record an audit event.
   *
   * @param record the audit record
   */
  public static void record(AuditRecord record) {
    if (record != null) {
      records.add(record);
      while (records.size() > MAX_RECORDS) {
        records.remove(0);
      }
      try {
        InterceptorEventPublisher.getInstance()
            .publish(
                LifecycleEvents.registry(
                    "audit",
                    record.getOperation(),
                    true,
                    record.getTarget() + "|result=" + record.getResult()));
      } catch (Throwable t) {
        // never break audit recording because of listener failures
      }
    }
  }

  /**
   * Record an interception audit event.
   *
   * @param className the intercepted class
   * @param methodName the intercepted method
   * @param allowed whether the interception was allowed
   */
  public static void recordInterception(String className, String methodName, boolean allowed) {
    record(
        AuditRecord.builder()
            .operation("INTERCEPT")
            .target(className + "." + methodName)
            .result(allowed ? "ALLOWED" : "DENIED")
            .detail("class", className)
            .detail("method", methodName)
            .build());
  }

  /**
   * Record a config change audit event.
   *
   * @param key the config key
   * @param source who made the change
   * @param oldValue previous value
   * @param newValue new value
   */
  public static void recordConfigChange(
      String key, String source, String oldValue, String newValue) {
    record(
        AuditRecord.builder()
            .operation("CONFIG_CHANGE")
            .principal(source)
            .target(key)
            .result("SUCCESS")
            .detail("oldValue", oldValue)
            .detail("newValue", newValue)
            .build());
  }

  /** Get all audit records. */
  public static List<AuditRecord> getRecords() {
    return Collections.unmodifiableList(new ArrayList<>(records));
  }

  /** Get audit records filtered by operation type. */
  public static List<AuditRecord> getRecords(String operation) {
    List<AuditRecord> filtered = new ArrayList<>();
    for (AuditRecord r : records) {
      if (operation.equals(r.getOperation())) {
        filtered.add(r);
      }
    }
    return Collections.unmodifiableList(filtered);
  }

  /** Set the security policy. */
  public static void setPolicy(SecurityPolicy newPolicy) {
    policy = newPolicy != null ? newPolicy : SecurityPolicy.builder().build();
  }

  /** Get the current security policy. */
  public static SecurityPolicy getPolicy() {
    return policy;
  }

  /**
   * Check if interception is allowed under current policy.
   *
   * @param className the class to intercept
   * @param methodName the method to intercept
   * @return true if allowed
   */
  public static boolean isInterceptionAllowed(String className, String methodName) {
    boolean allowed = policy.isMethodAllowed(className, methodName);
    if (policy.shouldAuditAll()) {
      recordInterception(className, methodName, allowed);
    }
    return allowed;
  }

  /** Clear all audit records. */
  public static void clear() {
    records.clear();
  }

  /** Get record count. */
  public static int size() {
    return records.size();
  }
}
