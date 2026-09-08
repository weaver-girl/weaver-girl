package com.github.cc11001100.weavergirl.core.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdaptiveRateLimiterTest {

  private AdaptiveRateLimiter limiter;

  @BeforeEach
  void setUp() {
    limiter = new AdaptiveRateLimiter(100, 100, 0.8);
  }

  // === Basic Acquisition ===

  @Test
  void tryAcquire_firstRequest_allowed() {
    assertTrue(limiter.tryAcquire());
  }

  @Test
  void tryAcquire_withinBurst_allowed() {
    for (int i = 0; i < 50; i++) {
      assertTrue(limiter.tryAcquire());
    }
    assertEquals(50, limiter.getAllowedCount());
  }

  @Test
  void tryAcquire_zeroPermits_allowed() {
    assertTrue(limiter.tryAcquire(0));
  }

  @Test
  void tryAcquire_negativePermits_allowed() {
    assertTrue(limiter.tryAcquire(-1));
  }

  @Test
  void tryAcquire_largeBurstEventuallyRejected() {
    // With 100 burst capacity, 200 requests should eventually reject
    int allowed = 0;
    for (int i = 0; i < 200; i++) {
      if (limiter.tryAcquire()) allowed++;
    }
    assertTrue(allowed < 200, "Should have rejected some requests");
    assertTrue(limiter.getRejectedCount() > 0);
  }

  // === Statistics ===

  @Test
  void getAllowedCount_increments() {
    limiter.tryAcquire();
    limiter.tryAcquire();
    assertEquals(2, limiter.getAllowedCount());
  }

  @Test
  void getRejectedCount_increments() {
    // Exhaust tokens
    for (int i = 0; i < 300; i++) {
      limiter.tryAcquire();
    }
    assertTrue(limiter.getRejectedCount() > 0);
  }

  @Test
  void getTotalCount_sumOfAllowedAndRejected() {
    for (int i = 0; i < 300; i++) {
      limiter.tryAcquire();
    }
    assertEquals(limiter.getTotalCount(), limiter.getAllowedCount() + limiter.getRejectedCount());
  }

  @Test
  void getRejectionRate_noRequests_returnsZero() {
    assertEquals(0.0, limiter.getRejectionRate(), 0.01);
  }

  @Test
  void getRejectionRate_allAllowed_returnsZero() {
    limiter.tryAcquire();
    assertEquals(0.0, limiter.getRejectionRate(), 0.01);
  }

  @Test
  void getRejectionRate_mixed_correctRate() {
    for (int i = 0; i < 300; i++) {
      limiter.tryAcquire();
    }
    double rate = limiter.getRejectionRate();
    assertTrue(rate > 0 && rate < 1.0);
  }

  @Test
  void resetStats_clearsCounters() {
    for (int i = 0; i < 300; i++) {
      limiter.tryAcquire();
    }
    limiter.resetStats();
    assertEquals(0, limiter.getAllowedCount());
    assertEquals(0, limiter.getRejectedCount());
  }

  // === Adaptive Rate ===

  @Test
  void updateLoad_belowThreshold_noReduction() {
    limiter.updateLoad(0.5);
    assertEquals(100, limiter.getCurrentRate());
  }

  @Test
  void updateLoad_aboveThreshold_reducesRate() {
    limiter.updateLoad(0.9);
    assertTrue(limiter.getCurrentRate() < 100);
    assertTrue(limiter.getCurrentRate() >= 1);
  }

  @Test
  void updateLoad_atMaxLoad_maxReduction() {
    limiter.updateLoad(1.0);
    assertTrue(limiter.getCurrentRate() < 100);
    assertTrue(limiter.getCurrentRate() >= 1);
  }

  @Test
  void updateLoad_negativeTreatedAsZero() {
    limiter.updateLoad(-0.5);
    assertEquals(100, limiter.getCurrentRate());
  }

  @Test
  void updateLoad_aboveOneTreatedAsOne() {
    limiter.updateLoad(2.0);
    assertTrue(limiter.getCurrentRate() < 100);
  }

  @Test
  void updateLoad_returnsToBaseAfterLoadDrops() {
    limiter.updateLoad(0.95);
    assertTrue(limiter.getCurrentRate() < 100);
    limiter.updateLoad(0.3);
    assertEquals(100, limiter.getCurrentRate());
  }

  // === Configuration ===

  @Test
  void getBaseRate_returnsConfigured() {
    assertEquals(100, limiter.getBaseRate());
  }

  @Test
  void getMaxBurst_returnsConfigured() {
    assertEquals(100, limiter.getMaxBurst());
  }

  @Test
  void getLoadThreshold_returnsConfigured() {
    assertEquals(0.8, limiter.getLoadThreshold(), 0.01);
  }

  @Test
  void setBaseRate_updatesRate() {
    limiter.setBaseRate(200);
    assertEquals(200, limiter.getBaseRate());
    assertEquals(200, limiter.getCurrentRate());
  }

  @Test
  void setBaseRate_throwsForZero() {
    assertThrows(IllegalArgumentException.class, () -> limiter.setBaseRate(0));
  }

  @Test
  void setBaseRate_throwsForNegative() {
    assertThrows(IllegalArgumentException.class, () -> limiter.setBaseRate(-1));
  }

  // === Constructor Validation ===

  @Test
  void constructor_singleArg_createsLimiter() {
    AdaptiveRateLimiter l = new AdaptiveRateLimiter(500);
    assertEquals(500, l.getBaseRate());
    assertEquals(500, l.getMaxBurst());
  }

  @Test
  void constructor_throwsForZeroPermits() {
    assertThrows(IllegalArgumentException.class, () -> new AdaptiveRateLimiter(0));
  }

  @Test
  void constructor_throwsForZeroBurst() {
    assertThrows(IllegalArgumentException.class, () -> new AdaptiveRateLimiter(100, 0, 0.8));
  }

  // === Token Inspection ===

  @Test
  void getAvailableTokens_returnsPositiveAfterInit() {
    assertTrue(limiter.getAvailableTokens() > 0);
  }

  // === toString ===

  @Test
  void toString_containsRateInfo() {
    limiter.tryAcquire();
    String str = limiter.toString();
    assertTrue(str.contains("AdaptiveRateLimiter"));
    assertTrue(str.contains("allowed=1"));
  }

  // === Multi-permit Acquisition ===

  @Test
  void tryAcquire_multiplePermits() {
    AdaptiveRateLimiter fresh = new AdaptiveRateLimiter(1000, 1000, 0.8);
    assertTrue(fresh.tryAcquire(10));
    // Single acquisition counts as 1 allowed request
    assertEquals(1, fresh.getAllowedCount());
  }

  @Test
  void tryAcquire_tooManyPermits_rejected() {
    AdaptiveRateLimiter fresh = new AdaptiveRateLimiter(100, 50, 0.8);
    assertFalse(fresh.tryAcquire(200));
    assertEquals(0, fresh.getAllowedCount());
    assertEquals(1, fresh.getRejectedCount());
  }
}
