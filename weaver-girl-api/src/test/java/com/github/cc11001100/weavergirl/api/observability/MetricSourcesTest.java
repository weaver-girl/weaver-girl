package com.github.cc11001100.weavergirl.api.observability;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MetricSourcesTest {

  @AfterEach
  void tearDown() {
    MetricSources.clear();
  }

  @Test
  void sourcesAreCollectedInRegistrationOrder() {
    List<String> collected = new ArrayList<>();
    MetricSources.register(named("second", collected));
    MetricSources.register(named("first", collected));

    assertEquals(2, MetricSources.getSources().size());
    for (MetricSource source : MetricSources.getSources()) {
      source.collect(new NoopCollector());
    }
    assertEquals(java.util.Arrays.asList("second", "first"), collected);
  }

  @Test
  void registerAndUnregisterAreIdempotent() {
    MetricSource source = named("one", new ArrayList<>());
    MetricSources.register(source);
    MetricSources.register(source);
    assertEquals(1, MetricSources.getSources().size());

    MetricSources.unregister(source);
    MetricSources.unregister(source);
    assertTrue(MetricSources.getSources().isEmpty());
  }

  @Test
  void nullSourcesAreIgnored() {
    MetricSources.register(null);
    MetricSources.unregister(null);
    assertTrue(MetricSources.getSources().isEmpty());
  }

  private static MetricSource named(String name, List<String> collected) {
    return new MetricSource() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public void collect(MetricCollector collector) {
        collected.add(name);
      }
    };
  }

  private static final class NoopCollector implements MetricCollector {
    @Override
    public void gauge(String name, String help, double value) {}

    @Override
    public void gauge(String name, String help, Map<String, String> labels, double value) {}

    @Override
    public void counter(String name, String help, double value) {}

    @Override
    public void counter(String name, String help, Map<String, String> labels, double value) {}
  }
}
