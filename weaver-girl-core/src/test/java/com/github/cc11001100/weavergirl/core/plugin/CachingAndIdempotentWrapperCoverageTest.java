package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.CacheEvict;
import com.github.cc11001100.weavergirl.annotation.CacheResult;
import com.github.cc11001100.weavergirl.annotation.Idempotent;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@code @CacheResult}, {@code @CacheEvict}, and {@code @Idempotent} wrapper
 * methods in {@link AnnotationPluginLoader} (cache hit/miss/expiry, key-building branches, evict
 * scoping, and idempotency short-circuiting).
 *
 * <p>{@code AnnotationPluginLoader}'s cache/idempotency stores are static and shared across the
 * whole test JVM, so every cache key/prefix used here is prefixed with {@code cwct} to avoid
 * colliding with other tests (e.g. {@code AnnotationAllDimensionsTest} uses keys like {@code
 * "compute"}, {@code "process"}, {@code "submit"}).
 */
class CachingAndIdempotentWrapperCoverageTest {

  static class Target {}

  @WeaveClass(target = "com.example.CachingWrapperCoverageService")
  public static class CachingAndIdempotentInterceptor {

    // --- @CacheResult ---

    // Default ttlMs (0 -> no expiry) and default keyArgIndices (-> Arrays.toString fallback key).
    @CacheResult(value = "cwctFetchA")
    public void cacheFetchA(MethodInvocation inv) {}

    // Positive ttlMs (-> ttlMs>0 branch) and explicit keyArgIndices (-> custom key branch).
    @CacheResult(value = "cwctFetchB", ttlMs = 1, keyArgIndices = {0})
    public void cacheFetchB(MethodInvocation inv) {}

    // Used to prove a null return value is never cached.
    @CacheResult(value = "cwctFetchNull")
    public void cacheFetchNull(MethodInvocation inv) {}

    // Populates entries under an explicit keyPrefix for the single-key evict test.
    @CacheResult(value = "cwctDataA", keyPrefix = "cwctPrefixA")
    public void cacheDataA(MethodInvocation inv) {}

    // Populates entries under an explicit keyPrefix for the bulk (allEntries) evict test.
    @CacheResult(value = "cwctDataB", keyPrefix = "cwctPrefixB")
    public void cacheDataB(MethodInvocation inv) {}

    // Populates an entry under a keyPrefix matching the evict method's own value(), to exercise
    // CacheEvict's keyPrefix().isEmpty() ? value() : keyPrefix() fallback.
    @CacheResult(value = "cwctDataFallback", keyPrefix = "cwctDeleteFallback")
    public void cacheDataFallback(MethodInvocation inv) {}

    // --- @CacheEvict ---

    // allEntries=false, beforeInvocation=false (default) -> single exact-key evict in after().
    @CacheEvict(value = "cwctDeleteA", keyPrefix = "cwctPrefixA")
    public void evictA(MethodInvocation inv) {}

    // allEntries=true, beforeInvocation=true -> bulk prefix evict in before().
    @CacheEvict(value = "cwctDeleteB", keyPrefix = "cwctPrefixB", allEntries = true, beforeInvocation = true)
    public void evictB(MethodInvocation inv) {}

    // keyPrefix left empty -> falls back to using value() ("cwctDeleteFallback") as the prefix.
    @CacheEvict(value = "cwctDeleteFallback")
    public void evictFallback(MethodInvocation inv) {}

    // --- @Idempotent ---

    // Default keyArgIndices -> Arrays.toString(args) fallback key.
    @Idempotent(value = "cwctCreateOrder")
    public void idempotentCreateOrder(MethodInvocation inv) {}

    // Explicit keyArgIndices -> custom key built from a subset of arguments.
    @Idempotent(value = "cwctChargeCard", keyArgIndices = {0})
    public void idempotentChargeCard(MethodInvocation inv) {}

    // Used to prove a null return value is never stored as idempotent.
    @Idempotent(value = "cwctNoopAction")
    public void idempotentNoopAction(MethodInvocation inv) {}
  }

  private AnnotationPluginLoader loader;
  private InterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new DefaultInterceptorRegistry();
    Set<Class<?>> classes = new HashSet<>();
    classes.add(CachingAndIdempotentInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);
  }

  private Interceptor interceptorFor(String methodName) {
    return registry.getAllDefinitions().stream()
        .filter(d -> d.getName().endsWith("-" + methodName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no definition for " + methodName))
        .getInterceptor();
  }

  private MethodInvocation invocation(String methodName, Object... args) {
    return new MethodInvocation(CachingAndIdempotentInterceptor.class, methodName, new Target(), args);
  }

  // ==================== @CacheResult ====================

  @Test
  void cacheResult_missThenHit_usesFallbackKeyAndNoExpiryWhenTtlMsIsZero() {
    Interceptor interceptor = interceptorFor("cwctFetchA");

    MethodInvocation first = invocation("cwctFetchA", "alpha");
    interceptor.before(first);
    assertFalse(first.isSkipped(), "first call must be a cache miss");
    assertNull(first.getAttachment("cache.hit"));
    assertNotNull(first.getAttachment("cache.key"), "miss path should record the cache key");
    first.initReturnValue("result-alpha");
    interceptor.after(first);

    MethodInvocation second = invocation("cwctFetchA", "alpha");
    interceptor.before(second);
    assertTrue(second.isSkipped(), "second call with same args must be a cache hit");
    assertEquals("result-alpha", second.getReturnValue());
    assertEquals(Boolean.TRUE, second.getAttachment("cache.hit"));
  }

  @Test
  void cacheResult_expiredEntry_fallsThroughToFreshMiss() throws InterruptedException {
    Interceptor interceptor = interceptorFor("cwctFetchB");

    MethodInvocation first = invocation("cwctFetchB", "k1", "ignoredSecondArg");
    interceptor.before(first);
    assertFalse(first.isSkipped());
    first.initReturnValue("result-b1");
    interceptor.after(first);

    // Confirm it is cached immediately (proves the keyArgIndices={0} custom key branch works,
    // since only arg[0] is part of the key -- a differing second arg still hits).
    MethodInvocation hit = invocation("cwctFetchB", "k1", "differentSecondArg");
    interceptor.before(hit);
    assertTrue(hit.isSkipped());
    assertEquals("result-b1", hit.getReturnValue());

    // ttlMs=1 -- sleep past expiry, then the entry must be treated as expired.
    Thread.sleep(20);

    MethodInvocation afterExpiry = invocation("cwctFetchB", "k1", "yetAnotherSecondArg");
    interceptor.before(afterExpiry);
    assertFalse(afterExpiry.isSkipped(), "expired entry must not short-circuit");
    assertNull(afterExpiry.getAttachment("cache.hit"));
    assertNotNull(afterExpiry.getAttachment("cache.key"));
  }

  @Test
  void cacheResult_nullReturnValue_isNeverCached() {
    Interceptor interceptor = interceptorFor("cwctFetchNull");

    MethodInvocation first = invocation("cwctFetchNull", "x");
    interceptor.before(first);
    assertFalse(first.isSkipped());
    // Simulate the target method returning null: getReturnValue() stays null.
    interceptor.after(first);

    MethodInvocation second = invocation("cwctFetchNull", "x");
    interceptor.before(second);
    assertFalse(second.isSkipped(), "a null return value must never populate the cache");
    assertNull(second.getAttachment("cache.hit"));
  }

  // ==================== @CacheEvict ====================

  @Test
  void cacheEvict_singleKey_removesOnlyExactKey_inAfter() {
    Interceptor cacheInterceptor = interceptorFor("cwctDataA");
    Interceptor evictInterceptor = interceptorFor("cwctDeleteA");

    MethodInvocation populateX = invocation("cwctDataA", "x");
    cacheInterceptor.before(populateX);
    populateX.initReturnValue("valX");
    cacheInterceptor.after(populateX);

    MethodInvocation populateY = invocation("cwctDataA", "y");
    cacheInterceptor.before(populateY);
    populateY.initReturnValue("valY");
    cacheInterceptor.after(populateY);

    // Sanity: both entries are cached before eviction.
    MethodInvocation checkXBefore = invocation("cwctDataA", "x");
    cacheInterceptor.before(checkXBefore);
    assertTrue(checkXBefore.isSkipped());

    // beforeInvocation=false -> evicting must happen in after(), not before().
    MethodInvocation evict = invocation("cwctDeleteA", "x");
    evictInterceptor.before(evict);
    MethodInvocation stillCachedDuringBefore = invocation("cwctDataA", "x");
    cacheInterceptor.before(stillCachedDuringBefore);
    assertTrue(stillCachedDuringBefore.isSkipped(), "single-key evict must not run in before()");

    evictInterceptor.after(evict);

    MethodInvocation checkXAfter = invocation("cwctDataA", "x");
    cacheInterceptor.before(checkXAfter);
    assertFalse(checkXAfter.isSkipped(), "evicted key must be a fresh miss");

    MethodInvocation checkYAfter = invocation("cwctDataA", "y");
    cacheInterceptor.before(checkYAfter);
    assertTrue(checkYAfter.isSkipped(), "a different exact key sharing the prefix must survive");
    assertEquals("valY", checkYAfter.getReturnValue());
  }

  @Test
  void cacheEvict_allEntries_removesEveryKeySharingPrefix_inBefore() {
    Interceptor cacheInterceptor = interceptorFor("cwctDataB");
    Interceptor evictInterceptor = interceptorFor("cwctDeleteB");

    MethodInvocation populateP = invocation("cwctDataB", "p");
    cacheInterceptor.before(populateP);
    populateP.initReturnValue("v1");
    cacheInterceptor.after(populateP);

    MethodInvocation populateQ = invocation("cwctDataB", "q");
    cacheInterceptor.before(populateQ);
    populateQ.initReturnValue("v2");
    cacheInterceptor.after(populateQ);

    MethodInvocation checkPBefore = invocation("cwctDataB", "p");
    cacheInterceptor.before(checkPBefore);
    assertTrue(checkPBefore.isSkipped());

    // beforeInvocation=true -> bulk eviction must happen in before(), and allEntries=true means
    // every key with the shared prefix is removed regardless of the exact suffix.
    MethodInvocation evict = invocation("cwctDeleteB");
    evictInterceptor.before(evict);

    MethodInvocation checkPAfter = invocation("cwctDataB", "p");
    cacheInterceptor.before(checkPAfter);
    assertFalse(checkPAfter.isSkipped(), "bulk evict must remove entry p");

    MethodInvocation checkQAfter = invocation("cwctDataB", "q");
    cacheInterceptor.before(checkQAfter);
    assertFalse(checkQAfter.isSkipped(), "bulk evict must remove entry q too");
  }

  @Test
  void cacheEvict_keyPrefixFallsBackToValue_whenKeyPrefixEmpty() {
    Interceptor cacheInterceptor = interceptorFor("cwctDataFallback");
    Interceptor evictInterceptor = interceptorFor("cwctDeleteFallback");

    MethodInvocation populate = invocation("cwctDataFallback", "z");
    cacheInterceptor.before(populate);
    populate.initReturnValue("valZ");
    cacheInterceptor.after(populate);

    MethodInvocation checkBefore = invocation("cwctDataFallback", "z");
    cacheInterceptor.before(checkBefore);
    assertTrue(checkBefore.isSkipped());

    // evictFallback has no keyPrefix, so it must evict using its own value() ("cwctDeleteFallback")
    // as the prefix -- which is exactly the keyPrefix the cache entry above was populated under.
    MethodInvocation evict = invocation("cwctDeleteFallback", "z");
    evictInterceptor.after(evict);

    MethodInvocation checkAfter = invocation("cwctDataFallback", "z");
    cacheInterceptor.before(checkAfter);
    assertFalse(checkAfter.isSkipped(), "fallback-prefix evict must have removed the entry");
  }

  // ==================== @Idempotent ====================

  @Test
  void idempotent_missThenHit_usesFallbackKey() {
    Interceptor interceptor = interceptorFor("cwctCreateOrder");

    MethodInvocation first = invocation("cwctCreateOrder", "order1");
    interceptor.before(first);
    assertFalse(first.isSkipped());
    assertNull(first.getAttachment("idempotent.cached"));
    assertNotNull(first.getAttachment("idempotent.key"));
    first.initReturnValue("created-order1");
    interceptor.after(first);

    MethodInvocation second = invocation("cwctCreateOrder", "order1");
    interceptor.before(second);
    assertTrue(second.isSkipped(), "duplicate call with same args must be short-circuited");
    assertEquals("created-order1", second.getReturnValue());
    assertEquals(Boolean.TRUE, second.getAttachment("idempotent.cached"));
  }

  @Test
  void idempotent_keyArgIndices_buildsKeyFromSelectedArgumentsOnly() {
    Interceptor interceptor = interceptorFor("cwctChargeCard");

    MethodInvocation first = invocation("cwctChargeCard", "cardXYZ", 100);
    interceptor.before(first);
    assertFalse(first.isSkipped());
    first.initReturnValue("charged-1");
    interceptor.after(first);

    // Only arg[0] participates in the key, so a differing second argument must still hit.
    MethodInvocation second = invocation("cwctChargeCard", "cardXYZ", 200);
    interceptor.before(second);
    assertTrue(second.isSkipped());
    assertEquals("charged-1", second.getReturnValue());
    assertEquals(Boolean.TRUE, second.getAttachment("idempotent.cached"));
  }

  @Test
  void idempotent_nullReturnValue_isNeverStored() {
    Interceptor interceptor = interceptorFor("cwctNoopAction");

    MethodInvocation first = invocation("cwctNoopAction", "a");
    interceptor.before(first);
    assertFalse(first.isSkipped());
    // Simulate the target method returning null: getReturnValue() stays null.
    interceptor.after(first);

    MethodInvocation second = invocation("cwctNoopAction", "a");
    interceptor.before(second);
    assertFalse(second.isSkipped(), "a null return value must never populate the idempotency store");
    assertNull(second.getAttachment("idempotent.cached"));
  }
}
