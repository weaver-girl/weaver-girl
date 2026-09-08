package com.github.cc11001100.weavergirl.core.bench;

import com.github.cc11001100.weavergirl.core.exporter.SpanData;
import com.github.cc11001100.weavergirl.core.exporter.SpanExporter;
import com.github.cc11001100.weavergirl.core.exporter.SpanFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * JMH micro-benchmarks for span serialization/export paths.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@link SpanData#toOtlpJson()} JSON serialization
 *   <li>{@link SpanFormatter.LoggingSpanFormatter} export
 *   <li>{@link SpanFormatter.InMemorySpanFormatter} export
 *   <li>{@link SpanExporter#submit(SpanData)} + flush path
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class SpanSerializationBenchmark {

  private List<SpanData> spans;

  @Setup
  public void setup() {
    spans = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      SpanData span =
          SpanData.builder()
              .traceId("trace-" + i)
              .spanId("span-" + i)
              .parentSpanId(i == 0 ? null : "span-" + (i - 1))
              .operationName("bench-operation")
              .startTimeMs(System.currentTimeMillis())
              .durationMs(10)
              .status("OK")
              .attribute("key", "value-" + i)
              .build();
      spans.add(span);
    }
  }

  @Benchmark
  public String toOtlpJson_singleSpan() {
    return spans.get(0).toOtlpJson();
  }

  @Benchmark
  public List<String> toOtlpJson_batch() {
    List<String> out = new ArrayList<>(spans.size());
    for (SpanData span : spans) {
      out.add(span.toOtlpJson());
    }
    return out;
  }

  @Benchmark
  public void loggingFormatter_export() {
    SpanFormatter formatter = new SpanFormatter.LoggingSpanFormatter();
    formatter.export(spans);
  }

  @Benchmark
  public void inMemoryFormatter_export() {
    SpanFormatter.InMemorySpanFormatter formatter = new SpanFormatter.InMemorySpanFormatter();
    formatter.export(spans);
  }

  @Benchmark
  public int spanExporter_submitAndFlush() {
    SpanExporter exporter = new SpanExporter(100, 60_000, 1000);
    for (SpanData span : spans) {
      exporter.submit(span);
    }
    return exporter.flush();
  }
}
