package com.github.cc11001100.weavergirl.core.performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * High-performance, lock-free object pool for reducing GC pressure on hot paths.
 *
 * <p>Used to pool frequently-allocated objects like {@code MethodInvocation}
 * instances that are created and discarded on every intercepted method call.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Lock-free implementation using {@link ConcurrentLinkedQueue}</li>
 *   <li>Bounded pool size to prevent memory leaks</li>
 *   <li>Statistics tracking (hits, misses, evictions)</li>
 *   <li>Auto-shrink when pool exceeds capacity</li>
 * </ul>
 *
 * @param <T> the type of objects being pooled
 */
public class LockFreeObjectPool<T> {

    private static final Logger log = LoggerFactory.getLogger(LockFreeObjectPool.class);

    private static final int DEFAULT_MAX_SIZE = 2048;

    private final ConcurrentLinkedQueue<T> pool;
    private final AtomicInteger poolSize;
    private final int maxSize;
    private final Supplier<T> supplier;

    // Statistics
    private final AtomicInteger borrowCount = new AtomicInteger();
    private final AtomicInteger returnCount = new AtomicInteger();
    private final AtomicInteger hitCount = new AtomicInteger();
    private final AtomicInteger missCount = new AtomicInteger();
    private final AtomicInteger evictionCount = new AtomicInteger();

    /**
     * Create a pool with the default max size.
     *
     * @param supplier factory for creating new objects when the pool is empty
     */
    public LockFreeObjectPool(Supplier<T> supplier) {
        this(supplier, DEFAULT_MAX_SIZE);
    }

    /**
     * Create a pool with a custom max size.
     *
     * @param supplier factory for creating new objects when the pool is empty
     * @param maxSize maximum number of objects to keep in the pool
     */
    public LockFreeObjectPool(Supplier<T> supplier, int maxSize) {
        this.supplier = supplier;
        this.maxSize = maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
        this.pool = new ConcurrentLinkedQueue<>();
        this.poolSize = new AtomicInteger(0);
    }

    /**
     * Borrow an object from the pool. If the pool is empty, a new object is created.
     *
     * @return a pooled or newly created object
     */
    public T borrow() {
        borrowCount.incrementAndGet();
        T obj = pool.poll();
        if (obj != null) {
            poolSize.decrementAndGet();
            hitCount.incrementAndGet();
            return obj;
        }
        missCount.incrementAndGet();
        return supplier.get();
    }

    /**
     * Return an object to the pool. If the pool is at capacity, the object is discarded.
     *
     * @param obj the object to return
     */
    public void release(T obj) {
        if (obj == null) return;
        returnCount.incrementAndGet();

        int current = poolSize.get();
        if (current >= maxSize) {
            evictionCount.incrementAndGet();
            return; // Discard — pool is full
        }

        if (poolSize.incrementAndGet() <= maxSize) {
            pool.offer(obj);
        } else {
            poolSize.decrementAndGet();
            evictionCount.incrementAndGet();
        }
    }

    /**
     * Get the current pool size.
     */
    public int size() {
        return poolSize.get();
    }

    /**
     * Get the maximum pool size.
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * Clear all objects from the pool.
     */
    public void clear() {
        int cleared = poolSize.getAndSet(0);
        pool.clear();
        if (cleared > 0 && log.isDebugEnabled()) {
            log.debug("[ObjectPool] Cleared {} objects", cleared);
        }
    }

    // --- Statistics ---

    public long getBorrowCount() { return borrowCount.get(); }
    public long getReturnCount() { return returnCount.get(); }
    public long getHitCount() { return hitCount.get(); }
    public long getMissCount() { return missCount.get(); }
    public long getEvictionCount() { return evictionCount.get(); }

    /**
     * Calculate the pool hit rate (0.0 to 1.0).
     */
    public double getHitRate() {
        long borrows = borrowCount.get();
        return borrows > 0 ? (double) hitCount.get() / borrows : 0.0;
    }

    /**
     * Get a snapshot of pool statistics.
     */
    public PoolStats getStats() {
        return new PoolStats(
                poolSize.get(), maxSize,
                borrowCount.get(), returnCount.get(),
                hitCount.get(), missCount.get(),
                evictionCount.get(), getHitRate()
        );
    }

    /**
     * Reset all statistics counters.
     */
    public void resetStats() {
        borrowCount.set(0);
        returnCount.set(0);
        hitCount.set(0);
        missCount.set(0);
        evictionCount.set(0);
    }

    @Override
    public String toString() {
        return String.format("LockFreeObjectPool{size=%d/%d, hitRate=%.2f%%, borrows=%d}",
                poolSize.get(), maxSize, getHitRate() * 100, borrowCount.get());
    }

    /**
     * Immutable statistics snapshot.
     */
    public static class PoolStats {
        private final int poolSize;
        private final int maxSize;
        private final long borrowCount;
        private final long returnCount;
        private final long hitCount;
        private final long missCount;
        private final long evictionCount;
        private final double hitRate;

        PoolStats(int poolSize, int maxSize, long borrowCount, long returnCount,
                  long hitCount, long missCount, long evictionCount, double hitRate) {
            this.poolSize = poolSize;
            this.maxSize = maxSize;
            this.borrowCount = borrowCount;
            this.returnCount = returnCount;
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.evictionCount = evictionCount;
            this.hitRate = hitRate;
        }

        public int getPoolSize() { return poolSize; }
        public int getMaxSize() { return maxSize; }
        public long getBorrowCount() { return borrowCount; }
        public long getReturnCount() { return returnCount; }
        public long getHitCount() { return hitCount; }
        public long getMissCount() { return missCount; }
        public long getEvictionCount() { return evictionCount; }
        public double getHitRate() { return hitRate; }

        @Override
        public String toString() {
            return String.format("PoolStats{size=%d/%d, hitRate=%.2f%%, borrows=%d, evictions=%d}",
                    poolSize, maxSize, hitRate * 100, borrowCount, evictionCount);
        }
    }
}
