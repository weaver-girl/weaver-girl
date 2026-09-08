package com.github.cc11001100.weavergirl.api.sampling;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.*;

/** Tests for Advanced Sampling Strategies (P52). */
class SamplingStrategyTest {

  @AfterEach
  void tearDown() {
    // Reset registry active strategy
    SamplingStrategyRegistry.setActive("fixed");
  }

  // ===== SamplingStrategy interface =====

  @Test
  void strategy_defaultMethods() {
    SamplingStrategy strategy =
        new SamplingStrategy() {
          @Override
          public String name() {
            return "test-default";
          }

          @Override
          public boolean shouldSample() {
            return true;
          }
        };
    assertTrue(strategy.shouldSample());
    assertDoesNotThrow(() -> strategy.updateMetrics(100, 50.0));
    assertDoesNotThrow(() -> strategy.reset());
  }

  // ===== FixedSamplingStrategy =====

  @Test
  void fixed_rate1_samplesAll() {
    FixedSamplingStrategy strategy = new FixedSamplingStrategy(1);
    for (int i = 0; i < 100; i++) {
      assertTrue(strategy.shouldSample());
    }
  }

  @Test
  void fixed_rate10_samples1in10() {
    FixedSamplingStrategy strategy = new FixedSamplingStrategy(10);
    int sampled = 0;
    for (int i = 0; i < 100; i++) {
      if (strategy.shouldSample()) sampled++;
    }
    assertEquals(10, sampled);
  }

  @Test
  void fixed_rateClampedTo1() {
    FixedSamplingStrategy strategy = new FixedSamplingStrategy(0);
    assertEquals(1, strategy.getRate());
    strategy.setRate(-5);
    assertEquals(1, strategy.getRate());
  }

  @Test
  void fixed_reset() {
    FixedSamplingStrategy strategy = new FixedSamplingStrategy(5);
    for (int i = 0; i < 3; i++) strategy.shouldSample();
    strategy.reset();
    // After reset, counter is 0, next call makes it 1, not divisible by 5
    assertFalse(strategy.shouldSample());
  }

  // ===== ProbabilisticSamplingStrategy =====

  @Test
  void probabilistic_alwaysSample() {
    ProbabilisticSamplingStrategy strategy = new ProbabilisticSamplingStrategy(1.0);
    for (int i = 0; i < 100; i++) {
      assertTrue(strategy.shouldSample());
    }
  }

  @Test
  void probabilistic_neverSample() {
    ProbabilisticSamplingStrategy strategy = new ProbabilisticSamplingStrategy(0.0);
    for (int i = 0; i < 100; i++) {
      assertFalse(strategy.shouldSample());
    }
  }

  @Test
  void probabilistic_50percentApproximate() {
    ProbabilisticSamplingStrategy strategy = new ProbabilisticSamplingStrategy(0.5);
    int sampled = 0;
    int total = 10000;
    for (int i = 0; i < total; i++) {
      if (strategy.shouldSample()) sampled++;
    }
    // Should be approximately 50% with some tolerance
    assertTrue(sampled > 4000 && sampled < 6000, "Expected ~5000, got " + sampled);
  }

  @Test
  void probabilistic_actualRate() {
    ProbabilisticSamplingStrategy strategy = new ProbabilisticSamplingStrategy(1.0);
    for (int i = 0; i < 100; i++) strategy.shouldSample();
    assertEquals(1.0, strategy.getActualRate(), 0.01);
  }

  @Test
  void probabilistic_clampedProbability() {
    ProbabilisticSamplingStrategy strategy = new ProbabilisticSamplingStrategy(2.0);
    assertEquals(1.0, strategy.getProbability());
    strategy.setProbability(-1.0);
    assertEquals(0.0, strategy.getProbability());
  }

  @Test
  void probabilistic_reset() {
    ProbabilisticSamplingStrategy strategy = new ProbabilisticSamplingStrategy(1.0);
    for (int i = 0; i < 50; i++) strategy.shouldSample();
    strategy.reset();
    assertEquals(0.0, strategy.getActualRate(), 0.01);
  }

  // ===== SamplingStrategyRegistry =====

  @Test
  void registry_hasBuiltInStrategies() {
    Set<String> names = SamplingStrategyRegistry.getStrategyNames();
    assertTrue(names.contains("fixed"));
    assertTrue(names.contains("probabilistic"));
  }

  @Test
  void registry_registerAndGet() {
    SamplingStrategy custom =
        new SamplingStrategy() {
          @Override
          public String name() {
            return "custom-test";
          }

          @Override
          public boolean shouldSample() {
            return true;
          }
        };

    SamplingStrategyRegistry.register(custom);
    assertNotNull(SamplingStrategyRegistry.get("custom-test"));
    assertSame(custom, SamplingStrategyRegistry.get("custom-test"));
  }

  @Test
  void registry_setActive() {
    assertTrue(SamplingStrategyRegistry.setActive("fixed"));
    assertEquals("fixed", SamplingStrategyRegistry.getActiveName());

    assertFalse(SamplingStrategyRegistry.setActive("nonexistent"));
  }

  @Test
  void registry_getActiveReturnsDefault() {
    SamplingStrategy active = SamplingStrategyRegistry.getActive();
    assertNotNull(active);
  }

  @Test
  void registry_registerNullIgnored() {
    assertDoesNotThrow(() -> SamplingStrategyRegistry.register(null));
  }
}
