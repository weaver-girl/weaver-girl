package com.github.cc11001100.weavergirl.api.context;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.tenant.TenantContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for TenantContextPropagator.
 *
 * <p>Covers: tenant propagation to worker thread, ThreadContext bridge, cleanup restores worker's
 * prior tenant, no-leak when no tenant set, and registry registration.
 */
class TenantContextPropagatorTest {

  @AfterEach
  void tearDown() {
    ThreadContext.clear();
    TenantContext.clear();
    Tracer.clearCurrentSpan();
    ContextPropagatorRegistry.resetForTest();
  }

  @Test
  void tenantPropagator_propagatesTenantToWorkerThread() throws Exception {
    TenantContext.setTenantId("customer-123");
    TenantContext.setTenantGroup("enterprise");

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      AtomicReference<String> workerTenantId = new AtomicReference<>();
      AtomicReference<String> workerTenantGroup = new AtomicReference<>();

      executor
          .submit(
              ContextPropagators.wrap(
                  () -> {
                    workerTenantId.set(TenantContext.getTenantId());
                    workerTenantGroup.set(TenantContext.getTenantGroup());
                  }))
          .get(5, TimeUnit.SECONDS);

      assertEquals("customer-123", workerTenantId.get(), "tenantId should be propagated");
      assertEquals("enterprise", workerTenantGroup.get(), "tenantGroup should be propagated");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void tenantPropagator_bridgesIntoThreadContext() throws Exception {
    TenantContext.setTenantId("customer-456");

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      AtomicReference<String> tcTenantId = new AtomicReference<>();

      executor
          .submit(
              ContextPropagators.wrap(
                  () -> {
                    tcTenantId.set(ThreadContext.get("tenantId"));
                  }))
          .get(5, TimeUnit.SECONDS);

      assertEquals(
          "customer-456", tcTenantId.get(), "tenantId should be bridged into ThreadContext");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void tenantPropagator_cleanupRestoresWorkerPriorTenant() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      // Worker starts with a tenant
      executor
          .submit(
              () -> {
                TenantContext.setTenantId("worker-tenant");
              })
          .get(5, TimeUnit.SECONDS);

      // Submit task with different tenant
      TenantContext.setTenantId("task-tenant");
      executor
          .submit(
              ContextPropagators.wrap(
                  () -> {
                    assertEquals("task-tenant", TenantContext.getTenantId());
                  }))
          .get(5, TimeUnit.SECONDS);

      // Worker's original tenant should be restored
      AtomicReference<String> afterTenant = new AtomicReference<>();
      executor.submit(() -> afterTenant.set(TenantContext.getTenantId())).get(5, TimeUnit.SECONDS);

      assertEquals(
          "worker-tenant", afterTenant.get(), "Worker's tenant should be restored after task");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void tenantPropagator_noTenant_noLeak() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      // No tenant set on submitting thread
      executor
          .submit(
              ContextPropagators.wrap(
                  () -> {
                    assertNull(
                        TenantContext.getTenantId(),
                        "No tenant should be propagated when none is set");
                  }))
          .get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void tenantPropagator_registeredInRegistry() {
    assertNotNull(ContextPropagatorRegistry.get("tenant-context"));
    assertEquals(-50, ContextPropagatorRegistry.get("tenant-context").priority());
  }
}
