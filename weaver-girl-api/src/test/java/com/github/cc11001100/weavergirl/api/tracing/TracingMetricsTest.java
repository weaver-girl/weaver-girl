package com.github.cc11001100.weavergirl.api.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The tracer's own counters: started, ended, published and dropped. */
class TracingMetricsTest {

  @BeforeEach
  @AfterEach
  void reset() {
    TracingMetrics.reset();
    SpanProcessors.clear();
    Tracer.clearCurrentSpan();
  }

  @Test
  void startingAndEndingOneSpanCountsOneStartedOneEndedOnePublished() {
    Tracer.startSpan("op");
    Tracer.endSpan("op", "OK");

    assertEquals(1, TracingMetrics.getStarted());
    assertEquals(1, TracingMetrics.getEnded());
    assertEquals(1, TracingMetrics.getPublished());
    assertEquals(0, TracingMetrics.getDropped());
  }

  @Test
  void aChildSpanCountsAsAnotherStartedSpan() {
    Tracer.startSpan("root");
    Tracer.startChildSpan("child");

    assertEquals(2, TracingMetrics.getStarted());
  }

  @Test
  void startingAChildWithoutAParentCountsOneSpanNotTwo() {
    Tracer.startChildSpan("orphan");

    assertEquals(1, TracingMetrics.getStarted());
  }

  @Test
  void aSpanDroppedByGovernanceIsEndedAndDroppedButNotPublished() {
    SpanProcessors.register(
        new SpanProcessor() {
          @Override
          public String name() {
            return "dropper";
          }

          @Override
          public SpanContext process(SpanContext span) {
            return null;
          }
        });
    Tracer.startSpan("op");

    Tracer.endSpan("op", "OK");

    assertEquals(1, TracingMetrics.getStarted());
    assertEquals(1, TracingMetrics.getEnded());
    assertEquals(1, TracingMetrics.getDropped());
    assertEquals(0, TracingMetrics.getPublished());
  }

  @Test
  void endingWithNoActiveSpanCountsNothing() {
    Tracer.endSpan("op", "OK");

    assertEquals(0, TracingMetrics.getStarted());
    assertEquals(0, TracingMetrics.getEnded());
    assertEquals(0, TracingMetrics.getDropped());
    assertEquals(0, TracingMetrics.getPublished());
  }

  @Test
  void resetClearsEveryCounter() {
    Tracer.startSpan("op");
    Tracer.endSpan("op", "OK");

    TracingMetrics.reset();

    assertEquals(0, TracingMetrics.getStarted());
    assertEquals(0, TracingMetrics.getEnded());
    assertEquals(0, TracingMetrics.getPublished());
    assertEquals(0, TracingMetrics.getDropped());
  }
}