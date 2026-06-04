package com.github.cc11001100.weavergirl.core.performance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LockFreeObjectPoolTest {

    private AtomicInteger createCount;
    private LockFreeObjectPool<StringBuilder> pool;

    @BeforeEach
    void setUp() {
        createCount = new AtomicInteger();
        pool = new LockFreeObjectPool<>(() -> {
            createCount.incrementAndGet();
            return new StringBuilder();
        }, 100);
    }

    @Test
    void borrow_createsNewWhenEmpty() {
        StringBuilder sb = pool.borrow();
        assertNotNull(sb);
        assertEquals(1, createCount.get());
        assertEquals(1, pool.getMissCount());
        assertEquals(0, pool.getHitCount());
    }

    @Test
    void borrow_reusesReturnedObject() {
        StringBuilder sb1 = pool.borrow();
        pool.release(sb1);
        StringBuilder sb2 = pool.borrow();
        assertSame(sb1, sb2);
        assertEquals(1, createCount.get());
        assertEquals(1, pool.getHitCount());
    }

    @Test
    void release_toFullPool_evicts() {
        LockFreeObjectPool<StringBuilder> smallPool = new LockFreeObjectPool<>(
                StringBuilder::new, 5);

        // Fill pool
        for (int i = 0; i < 10; i++) {
            smallPool.release(new StringBuilder());
        }

        assertEquals(5, smallPool.size());
        assertTrue(smallPool.getEvictionCount() > 0);
    }

    @Test
    void release_null_ignored() {
        assertDoesNotThrow(() -> pool.release(null));
        assertEquals(0, pool.size());
    }

    @Test
    void size_tracksPoolSize() {
        assertEquals(0, pool.size());
        StringBuilder sb = pool.borrow();
        pool.release(sb);
        assertEquals(1, pool.size());
        pool.borrow();
        assertEquals(0, pool.size());
    }

    @Test
    void getMaxSize_returnsConfigured() {
        assertEquals(100, pool.getMaxSize());
    }

    @Test
    void clear_removesAllObjects() {
        for (int i = 0; i < 10; i++) {
            pool.release(new StringBuilder());
        }
        assertTrue(pool.size() > 0);
        pool.clear();
        assertEquals(0, pool.size());
    }

    @Test
    void getBorrowCount_incrementsOnBorrow() {
        pool.borrow();
        pool.borrow();
        assertEquals(2, pool.getBorrowCount());
    }

    @Test
    void getReturnCount_incrementsOnRelease() {
        pool.release(pool.borrow());
        pool.release(pool.borrow());
        assertEquals(2, pool.getReturnCount());
    }

    @Test
    void getHitRate_calculatesCorrectly() {
        // Miss on first borrow
        StringBuilder sb = pool.borrow();
        assertEquals(0.0, pool.getHitRate(), 0.01);

        // Return and borrow again — hit
        pool.release(sb);
        pool.borrow();
        assertEquals(0.5, pool.getHitRate(), 0.01);
    }

    @Test
    void getHitRate_noBorrows_returnsZero() {
        assertEquals(0.0, pool.getHitRate(), 0.01);
    }

    @Test
    void resetStats_clearsCounters() {
        pool.borrow();
        pool.release(pool.borrow());
        pool.resetStats();
        assertEquals(0, pool.getBorrowCount());
        assertEquals(0, pool.getHitCount());
        assertEquals(0, pool.getMissCount());
    }

    @Test
    void getStats_returnsSnapshot() {
        StringBuilder sb = pool.borrow(); // miss
        pool.release(sb);
        pool.borrow(); // hit
        LockFreeObjectPool.PoolStats stats = pool.getStats();
        assertEquals(2, stats.getBorrowCount());
        assertEquals(1, stats.getHitCount());
        assertEquals(0.5, stats.getHitRate(), 0.01);
    }

    @Test
    void toString_containsUsefulInfo() {
        pool.borrow();
        String str = pool.toString();
        assertTrue(str.contains("LockFreeObjectPool"));
        assertTrue(str.contains("borrows=1"));
    }

    @Test
    void poolStats_toString_containsInfo() {
        pool.borrow();
        LockFreeObjectPool.PoolStats stats = pool.getStats();
        String str = stats.toString();
        assertTrue(str.contains("PoolStats"));
        assertTrue(str.contains("borrows=1"));
    }

    @Test
    void constructor_defaultMaxSize() {
        LockFreeObjectPool<StringBuilder> defaultPool = new LockFreeObjectPool<>(StringBuilder::new);
        assertEquals(2048, defaultPool.getMaxSize());
    }

    @Test
    void constructor_zeroMaxSize_usesDefault() {
        LockFreeObjectPool<StringBuilder> p = new LockFreeObjectPool<>(StringBuilder::new, 0);
        assertEquals(2048, p.getMaxSize());
    }

    @Test
    void concurrentBorrowAndRelease() throws Exception {
        int threadCount = 20;
        int opsPerThread = 500;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        StringBuilder sb = pool.borrow();
                        sb.append("x");
                        pool.release(sb);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        assertEquals(threadCount * opsPerThread, pool.getBorrowCount());
    }

    @Test
    void poolStaysWithinMaxSize_underContention() throws Exception {
        LockFreeObjectPool<StringBuilder> smallPool = new LockFreeObjectPool<>(StringBuilder::new, 10);
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        StringBuilder sb = smallPool.borrow();
                        smallPool.release(sb);
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertTrue(smallPool.size() <= 10,
                "Pool size should not exceed max: " + smallPool.size());
    }
}
