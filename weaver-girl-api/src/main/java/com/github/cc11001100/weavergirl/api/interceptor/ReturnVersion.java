package com.github.cc11001100.weavergirl.api.interceptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-method return-value version tracker.
 *
 * <p>This class maintains a monotonically increasing version counter for each method signature.
 * Each time a method returns, the framework can increment the version and optionally record the
 * return value in a {@link ReturnSnapshot}. Consumers use the version to detect stale cached values
 * or to correlate observations across distributed traces.
 *
 * <h3>Thread safety</h3>
 *
 * <p>This class is thread-safe. Versions are incremented atomically using {@link AtomicLong}.
 *
 * <h3>Usage</h3>
 *
 * <pre>
 * // Get the next version for a method
 * long version = ReturnVersion.next("com.example.Service.process(String,int)");
 *
 * // Check if a cached value is stale
 * long current = ReturnVersion.get("com.example.Service.process(String,int)");
 * if (cachedVersion < current) {
 *     // Cache is stale, refresh
 * }</pre>
 *
 * @see ReturnSnapshot
 * @since 1.7.0
 */
public class ReturnVersion {

  private static final ConcurrentHashMap<String, AtomicLong> VERSION_MAP = new ConcurrentHashMap<>();

  private ReturnVersion() {}

  /**
   * Returns the current version for the given method signature, incrementing it by one.
   *
   * <p>If the method has not been seen before, this initializes its version to 1.
   *
   * @param methodSignature the method signature (e.g., {@code "com.example.Service.process(String,int)"})
   * @return the new version (>= 1)
   */
  public static long next(String methodSignature) {
    AtomicLong counter = VERSION_MAP.computeIfAbsent(methodSignature, k -> new AtomicLong(0));
    return counter.incrementAndGet();
  }

  /**
   * Returns the current version for the given method signature without incrementing it.
   *
   * <p>If the method has not been seen before, returns 0.
   *
   * @param methodSignature the method signature
   * @return the current version (0 if never called)
   */
  public static long get(String methodSignature) {
    AtomicLong counter = VERSION_MAP.get(methodSignature);
    return counter != null ? counter.get() : 0;
  }

  /**
   * Resets the version counter for the given method signature to 0.
   *
   * <p>This is primarily intended for testing.
   *
   * @param methodSignature the method signature
   */
  public static void reset(String methodSignature) {
    VERSION_MAP.remove(methodSignature);
  }

  /**
   * Clears all version counters.
   *
   * <p>This is primarily intended for testing.
   */
  public static void clear() {
    VERSION_MAP.clear();
  }
}
