package com.github.cc11001100.weavergirl.core.registry;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LRU cache layer on top of {@link DefaultInterceptorRegistry}.
 *
 * <p>Caches the result of {@code getInterceptorsForClass()} to avoid repeated scanning of all
 * definitions for pattern-based matchers. Thread-safe with bounded size.
 *
 * @since 1.1.0
 */
public class CachedInterceptorRegistry {

  private static final Logger log = LoggerFactory.getLogger(CachedInterceptorRegistry.class);

  private static final int DEFAULT_MAX_SIZE = 2048;

  private final DefaultInterceptorRegistry delegate;
  private final int maxSize;
  private final ConcurrentHashMap<String, List<InterceptorDefinition>> cache;
  private final AtomicLong hits = new AtomicLong(0);
  private final AtomicLong misses = new AtomicLong(0);

  public CachedInterceptorRegistry(DefaultInterceptorRegistry delegate) {
    this(delegate, DEFAULT_MAX_SIZE);
  }

  public CachedInterceptorRegistry(DefaultInterceptorRegistry delegate, int maxSize) {
    this.delegate = delegate;
    this.maxSize = maxSize;
    this.cache = new ConcurrentHashMap<>();
  }

  /**
   * Get interceptors for a class, using cache when possible.
   *
   * @param className the fully qualified class name
   * @return list of matching interceptor definitions
   */
  public List<InterceptorDefinition> getInterceptorsForClass(String className) {
    List<InterceptorDefinition> cached = cache.get(className);
    if (cached != null) {
      hits.incrementAndGet();
      return cached;
    }

    List<InterceptorDefinition> result = delegate.getInterceptorsForClass(className);
    if (cache.size() >= maxSize) {
      // Evict ~25% of entries to avoid unbounded growth
      evict(maxSize / 4);
    }
    cache.put(className, Collections.unmodifiableList(result));
    misses.incrementAndGet();
    return result;
  }

  /** Invalidate the cache. Called when definitions change. */
  public void invalidate() {
    cache.clear();
  }

  /** Get cache hit count. */
  public long getHits() {
    return hits.get();
  }

  /** Get cache miss count. */
  public long getMisses() {
    return misses.get();
  }

  /** Get cache hit rate. */
  public double getHitRate() {
    long total = hits.get() + misses.get();
    return total > 0 ? (double) hits.get() / total : 0.0;
  }

  /** Get current cache size. */
  public int size() {
    return cache.size();
  }

  private void evict(int count) {
    Iterator<String> it = cache.keySet().iterator();
    int evicted = 0;
    while (it.hasNext() && evicted < count) {
      it.next();
      it.remove();
      evicted++;
    }
  }
}
