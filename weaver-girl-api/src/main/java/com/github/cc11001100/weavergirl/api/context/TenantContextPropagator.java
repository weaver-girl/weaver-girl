package com.github.cc11001100.weavergirl.api.context;

import com.github.cc11001100.weavergirl.api.tenant.TenantContext;
import com.github.cc11001100.weavergirl.api.tenant.TenantSnapshot;

/**
 * Built-in {@link ContextPropagator} that propagates {@link TenantContext}
 * across thread boundaries.
 *
 * <p>When async tasks are submitted to an executor, the submitting thread's
 * tenant ID and tenant group are captured and restored on the worker thread.
 * Cleanup restores the worker's prior tenant context symmetrically.</p>
 *
 * <p>This propagator bridges tenant information into {@link ThreadContext}
 * under keys {@code "tenantId"} and {@code "tenantGroup"} during
 * {@link #restore}, so plugins reading ThreadContext can access the
 * propagated tenant without calling TenantContext directly.</p>
 *
 * @see TenantContext
 * @see TenantSnapshot
 * @since 1.8.0
 */
public class TenantContextPropagator implements ContextPropagator {

    static final class TenantSnapshotWrapper implements Snapshot {
        final TenantSnapshot tenantSnapshot;
        static final TenantSnapshotWrapper EMPTY =
                new TenantSnapshotWrapper(null);

        TenantSnapshotWrapper(TenantSnapshot tenantSnapshot) {
            this.tenantSnapshot = tenantSnapshot;
        }
    }

    @Override
    public String name() {
        return "tenant-context";
    }

    @Override
    public int priority() {
        // After ThreadContext (-200) and Tracer (-100), before default (0)
        return -50;
    }

    @Override
    public Snapshot capture() {
        TenantSnapshot ts = TenantContext.capture();
        return (ts.getTenantId() == null && ts.getTenantGroup() == null)
                ? TenantSnapshotWrapper.EMPTY
                : new TenantSnapshotWrapper(ts);
    }

    @Override
    public void restore(Snapshot snapshot) {
        if (!(snapshot instanceof TenantSnapshotWrapper)) {
            return;
        }
        TenantSnapshotWrapper w = (TenantSnapshotWrapper) snapshot;
        if (w.tenantSnapshot != null) {
            TenantContext.restore(w.tenantSnapshot);
            // Bridge: mirror tenantId and tenantGroup into ThreadContext
            if (w.tenantSnapshot.getTenantId() != null) {
                ThreadContext.put("tenantId", w.tenantSnapshot.getTenantId());
            }
            if (w.tenantSnapshot.getTenantGroup() != null) {
                ThreadContext.put("tenantGroup", w.tenantSnapshot.getTenantGroup());
            }
        }
    }

    @Override
    public void cleanup(Snapshot previous) {
        if (!(previous instanceof TenantSnapshotWrapper)) {
            return;
        }
        TenantSnapshotWrapper p = (TenantSnapshotWrapper) previous;
        if (p.tenantSnapshot != null) {
            TenantContext.restore(p.tenantSnapshot);
        } else {
            TenantContext.clear();
        }
        // ThreadContext keys "tenantId"/"tenantGroup" are cleaned up by
        // ThreadContextPropagator.cleanup() which restores the worker's
        // prior ThreadContext snapshot.
    }
}
