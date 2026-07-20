package com.github.cc11001100.weavergirl.api.context;

import com.github.cc11001100.weavergirl.api.tenant.TenantContext;
import com.github.cc11001100.weavergirl.api.tracing.SpanContext;
import com.github.cc11001100.weavergirl.api.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MdcPropagator.
 *
 * <p>Covers: MDC propagation to worker thread, cleanup restores worker's prior
 * MDC, traceId bridge from ThreadContext into MDC, tenantId bridge, no-leak
 * when no MDC set, and registry registration.</p>
 */
class MdcPropagatorTest {

    @AfterEach
    void tearDown() {
        ThreadContext.clear();
        TenantContext.clear();
        Tracer.clearCurrentSpan();
        MDC.clear();
        ContextPropagatorRegistry.resetForTest();
    }

    @Test
    void mdcPropagator_propagatesMdcToWorkerThread() throws Exception {
        MDC.put("requestId", "req-001");
        MDC.put("userId", "user-42");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> workerRequestId = new AtomicReference<>();
            AtomicReference<String> workerUserId = new AtomicReference<>();

            executor.submit(ContextPropagators.wrap(() -> {
                workerRequestId.set(MDC.get("requestId"));
                workerUserId.set(MDC.get("userId"));
            })).get(5, TimeUnit.SECONDS);

            assertEquals("req-001", workerRequestId.get(),
                    "MDC requestId should be propagated");
            assertEquals("user-42", workerUserId.get(),
                    "MDC userId should be propagated");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mdcPropagator_cleanupRestoresWorkerPriorMdc() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // Worker starts with MDC
            executor.submit(() -> {
                MDC.put("workerKey", "workerValue");
            }).get(5, TimeUnit.SECONDS);

            // Submit task with different MDC
            MDC.put("taskKey", "taskValue");
            executor.submit(ContextPropagators.wrap(() -> {
                assertEquals("taskValue", MDC.get("taskKey"));
            })).get(5, TimeUnit.SECONDS);

            // Worker's MDC should be restored
            AtomicReference<String> afterWorker = new AtomicReference<>();
            executor.submit(() -> afterWorker.set(MDC.get("workerKey")))
                    .get(5, TimeUnit.SECONDS);

            assertEquals("workerValue", afterWorker.get(),
                    "Worker's MDC should be restored after task");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mdcPropagator_bridgesTraceIdIntoMdc() throws Exception {
        SpanContext span = Tracer.startSpan();
        MDC.clear(); // Ensure no MDC before propagation

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> mdcTraceId = new AtomicReference<>();

            executor.submit(ContextPropagators.wrap(() -> {
                mdcTraceId.set(MDC.get("traceId"));
            })).get(5, TimeUnit.SECONDS);

            assertEquals(span.getTraceId(), mdcTraceId.get(),
                    "traceId should be bridged from ThreadContext into MDC");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mdcPropagator_bridgesTenantIdIntoMdc() throws Exception {
        TenantContext.setTenantId("tenant-789");
        MDC.clear();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> mdcTenantId = new AtomicReference<>();

            executor.submit(ContextPropagators.wrap(() -> {
                mdcTenantId.set(MDC.get("tenantId"));
            })).get(5, TimeUnit.SECONDS);

            assertEquals("tenant-789", mdcTenantId.get(),
                    "tenantId should be bridged from ThreadContext into MDC");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mdcPropagator_noMdc_noLeak() throws Exception {
        MDC.clear();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(ContextPropagators.wrap(() -> {
                assertNull(MDC.get("requestId"),
                        "No MDC should be propagated when none is set");
            })).get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mdcPropagator_registeredInRegistry() {
        assertNotNull(ContextPropagatorRegistry.get("mdc-context"));
        assertEquals(-40, ContextPropagatorRegistry.get("mdc-context").priority());
    }
}
