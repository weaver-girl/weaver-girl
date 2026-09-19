package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.Counted;
import com.github.cc11001100.weavergirl.annotation.Gauge;
import com.github.cc11001100.weavergirl.annotation.Histogram;
import com.github.cc11001100.weavergirl.annotation.Logged;
import com.github.cc11001100.weavergirl.annotation.Metric;
import com.github.cc11001100.weavergirl.annotation.SpanKind;
import com.github.cc11001100.weavergirl.annotation.Tag;
import com.github.cc11001100.weavergirl.annotation.Trace;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises every branch of the observability annotation wrappers in {@link
 * AnnotationPluginLoader}: {@code wrapWithTrace}, {@code wrapWithTag}, {@code wrapWithCounted},
 * {@code wrapWithLogged} (including all six log-level switch branches), {@code wrapWithMetric},
 * {@code wrapWithHistogram}, and {@code wrapWithGauge}.
 */
class ObservabilityWrapperCoverageTest {

  @WeaveClass(target = "com.example.ObservabilityService")
  public static class ObservabilityInterceptors {

    // ---- Trace ----
    @Trace(value = "traceA", spanName = "span.A", recordException = true)
    public void onTraceA(MethodInvocation inv) {}

    @Trace(value = "traceB", recordException = false)
    public void onTraceB(MethodInvocation inv) {}

    // ---- Tag (all target the same method so they share one wrapper instance) ----
    @Tag(value = "tagged", key = "k1", argIndex = 0)
    public void tagK1(MethodInvocation inv) {}

    @Tag(value = "tagged", key = "k2", tagValue = "staticVal")
    public void tagK2(MethodInvocation inv) {}

    @Tag(value = "tagged", key = "k3")
    public void tagK3(MethodInvocation inv) {}

    @Tag(value = "tagged", key = "k4", useReturn = true)
    public void tagK4(MethodInvocation inv) {}

    // ---- Counted ----
    @Counted(value = "countedA")
    public void onCountedA(MethodInvocation inv) {}

    @Counted(value = "countedB", recordFailuresOnly = true)
    public void onCountedB(MethodInvocation inv) {}

    // ---- Logged (one target method per level to hit every switch branch) ----
    @Logged(value = "loggedTrace", level = "TRACE", logArgs = true, logTime = true, logResult = true)
    public void onLoggedTrace(MethodInvocation inv) {}

    @Logged(
        value = "loggedDebug",
        level = "DEBUG",
        logArgs = false,
        logTime = false,
        logResult = false,
        prefix = "customPrefix")
    public void onLoggedDebug(MethodInvocation inv) {}

    @Logged(value = "loggedInfo", level = "INFO")
    public void onLoggedInfo(MethodInvocation inv) {}

    @Logged(value = "loggedWarn", level = "WARN")
    public void onLoggedWarn(MethodInvocation inv) {}

    @Logged(value = "loggedError", level = "ERROR")
    public void onLoggedError(MethodInvocation inv) {}

    @Logged(value = "loggedUnknown", level = "BOGUS")
    public void onLoggedUnknown(MethodInvocation inv) {}

    // ---- Metric ----
    @Metric(value = "metricA", name = "metricA", argIndex = 0, useReturn = true)
    public void onMetricA(MethodInvocation inv) {}

    @Metric(value = "metricB", name = "metricB")
    public void onMetricB(MethodInvocation inv) {}

    // ---- Histogram ----
    @Histogram(value = "histogramA")
    public void onHistogramA(MethodInvocation inv) {}

    @Histogram(value = "histogramB", name = "customHist")
    public void onHistogramB(MethodInvocation inv) {}

    // ---- Gauge ----
    @Gauge(value = "gaugeA", name = "gaugeA", argIndex = 0)
    public void onGaugeA(MethodInvocation inv) {}

    @Gauge(value = "gaugeB", name = "gaugeB", useReturn = true)
    public void onGaugeB(MethodInvocation inv) {}

    @Gauge(value = "gaugeC", name = "gaugeC")
    public void onGaugeC(MethodInvocation inv) {}
  }

  private AnnotationPluginLoader loader;
  private InterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new TestInterceptorRegistry();
    Set<Class<?>> classes = new HashSet<>();
    classes.add(ObservabilityInterceptors.class);
    loader.loadAnnotatedInterceptors(classes, registry);
  }

  private Interceptor interceptorFor(String targetMethod) {
    return registry.getAllDefinitions().stream()
        .filter(d -> d.getName().endsWith("-" + targetMethod))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no definition for " + targetMethod))
        .getInterceptor();
  }

  private MethodInvocation newInvocation(Object... args) {
    return new MethodInvocation(
        ObservabilityInterceptors.class, "invoke", "target", args);
  }

  // ==================== wrapWithTrace ====================

  @Test
  void trace_beforeSetsSpanAttachmentsAndAfterComputesElapsed_whenStartPresent() {
    Interceptor interceptor = interceptorFor("traceA");
    MethodInvocation inv = newInvocation();

    interceptor.before(inv);
    assertEquals("span.A", inv.getAttachment("trace.spanName"));
    assertEquals(SpanKind.INTERNAL, inv.getAttachment("trace.kind"));
    assertNotNull(inv.getAttachment("trace.startNanos"));

    interceptor.after(inv);
    Object elapsed = inv.getAttachment("trace.elapsedNanos");
    assertNotNull(elapsed);
    assertTrue((Long) elapsed >= 0);
  }

  @Test
  void trace_afterWithoutBeforeSkipsElapsedComputation_whenStartAbsent() {
    Interceptor interceptor = interceptorFor("traceA");
    MethodInvocation inv = newInvocation();

    interceptor.after(inv);
    assertNull(inv.getAttachment("trace.elapsedNanos"));
  }

  @Test
  void trace_onExceptionRecordsExceptionOnlyWhenRecordExceptionTrue() {
    Interceptor recording = interceptorFor("traceA");
    MethodInvocation invA = newInvocation();
    RuntimeException boom = new RuntimeException("boom");
    invA.setThrowable(boom);
    recording.onException(invA);
    assertSame(boom, invA.getAttachment("trace.exception"));

    Interceptor nonRecording = interceptorFor("traceB");
    MethodInvocation invB = newInvocation();
    // spanName defaults to empty -> falls back to ann.value() ("traceB")
    nonRecording.before(invB);
    assertEquals("traceB", invB.getAttachment("trace.spanName"));

    invB.setThrowable(new RuntimeException("nope"));
    nonRecording.onException(invB);
    assertNull(invB.getAttachment("trace.exception"));
  }

  // ==================== wrapWithTag ====================

  @Test
  void tag_beforeResolvesTagValueAcrossAllBranchCombinations() {
    Interceptor interceptor = interceptorFor("tagged");
    MethodInvocation inv = newInvocation("argVal");

    interceptor.before(inv);
    assertEquals("argVal", inv.getAttachment("tag.k1")); // tagValue empty && argIndex>=0, arg != null
    assertEquals("staticVal", inv.getAttachment("tag.k2")); // tagValue non-empty short-circuits
    assertEquals("", inv.getAttachment("tag.k3")); // tagValue empty but argIndex < 0
    assertEquals("", inv.getAttachment("tag.k4")); // same as k3 before after() overwrites it

    inv.initReturnValue("resultXYZ");
    interceptor.after(inv);
    assertEquals("resultXYZ", inv.getAttachment("tag.k4")); // useReturn=true overwrites
    assertEquals("argVal", inv.getAttachment("tag.k1")); // useReturn=false, unchanged

    assertDoesNotThrow(() -> interceptor.onException(inv));
  }

  @Test
  void tag_beforeUsesNullLiteral_whenArgIndexResolvedArgumentIsNull() {
    Interceptor interceptor = interceptorFor("tagged");
    MethodInvocation inv = newInvocation((Object) null);

    interceptor.before(inv);
    assertEquals("null", inv.getAttachment("tag.k1"));
  }

  // ==================== wrapWithCounted ====================

  @Test
  void counted_beforeIncrementsCounter_whenRecordFailuresOnlyFalse() {
    Interceptor interceptor = interceptorFor("countedA");
    MethodInvocation inv1 = newInvocation();
    interceptor.before(inv1);
    assertEquals(1L, inv1.getAttachment("counted.value"));

    MethodInvocation inv2 = newInvocation();
    interceptor.before(inv2);
    assertEquals(2L, inv2.getAttachment("counted.value"));
  }

  @Test
  void counted_beforeSkipsIncrement_whenRecordFailuresOnlyTrue_butOnExceptionAlwaysIncrements() {
    Interceptor interceptor = interceptorFor("countedB");
    MethodInvocation inv = newInvocation();

    interceptor.before(inv);
    assertNull(inv.getAttachment("counted.value"));

    MethodInvocation failureInv = newInvocation();
    interceptor.onException(failureInv);
    assertEquals(true, failureInv.getAttachment("counted.failure"));
    assertEquals(1L, failureInv.getAttachment("counted.value"));
  }

  // ==================== wrapWithLogged ====================

  @Test
  void logged_traceLevel_logsArgsAndTimeAndResult_prefixFallsBackToValue() {
    Interceptor interceptor = interceptorFor("loggedTrace");
    MethodInvocation inv = newInvocation("a", "b");

    assertDoesNotThrow(() -> interceptor.before(inv));
    assertNotNull(inv.getAttachment("logged.startNanos"));

    inv.initReturnValue("res1");
    assertDoesNotThrow(() -> interceptor.after(inv));
  }

  @Test
  void logged_debugLevel_skipsArgsTimeAndResult_customPrefix() {
    Interceptor interceptor = interceptorFor("loggedDebug");
    MethodInvocation inv = newInvocation();

    assertDoesNotThrow(() -> interceptor.before(inv));
    assertNotNull(inv.getAttachment("logged.startNanos"));
    assertDoesNotThrow(() -> interceptor.after(inv));
  }

  @Test
  void logged_infoLevel_onExceptionWithoutBefore_hitsStartNullAndHasExceptionBranches() {
    Interceptor interceptor = interceptorFor("loggedInfo");
    MethodInvocation inv = newInvocation();
    inv.setThrowable(new RuntimeException("bad"));

    assertDoesNotThrow(() -> interceptor.onException(inv));
  }

  @Test
  void logged_warnLevel_afterWithoutException_hitsHasExceptionFalseBranch() {
    Interceptor interceptor = interceptorFor("loggedWarn");
    MethodInvocation inv = newInvocation();

    interceptor.before(inv);
    assertDoesNotThrow(() -> interceptor.after(inv));
  }

  @Test
  void logged_errorLevel_onExceptionWithMessage() {
    Interceptor interceptor = interceptorFor("loggedError");
    MethodInvocation inv = newInvocation();
    inv.setThrowable(new RuntimeException("err"));

    assertDoesNotThrow(() -> interceptor.onException(inv));
  }

  @Test
  void logged_unrecognizedLevel_hitsSwitchDefaultBranch() {
    Interceptor interceptor = interceptorFor("loggedUnknown");
    MethodInvocation inv = newInvocation();

    assertDoesNotThrow(() -> interceptor.before(inv));
    assertDoesNotThrow(() -> interceptor.after(inv));
  }

  // ==================== wrapWithMetric ====================

  @Test
  void metric_beforeAndAfter_recordNumberArgAndReturn_skipNonNumber() {
    Interceptor interceptor = interceptorFor("metricA");

    MethodInvocation numericInv = newInvocation(42);
    interceptor.before(numericInv);
    assertEquals(42.0, numericInv.getAttachment("metric.metricA"));

    numericInv.initReturnValue(100);
    interceptor.after(numericInv);
    assertEquals(100.0, numericInv.getAttachment("metric.metricA"));

    assertDoesNotThrow(() -> interceptor.onException(numericInv));

    MethodInvocation nonNumericInv = newInvocation("notNumber");
    interceptor.before(nonNumericInv);
    assertNull(nonNumericInv.getAttachment("metric.metricA"));

    nonNumericInv.initReturnValue("alsoNotNumber");
    interceptor.after(nonNumericInv);
    assertNull(nonNumericInv.getAttachment("metric.metricA"));
  }

  @Test
  void metric_beforeAndAfter_skipWhenArgIndexNegativeAndUseReturnFalse() {
    Interceptor interceptor = interceptorFor("metricB");
    MethodInvocation inv = newInvocation(999);

    interceptor.before(inv);
    assertNull(inv.getAttachment("metric.metricB"));

    inv.initReturnValue(5);
    interceptor.after(inv);
    assertNull(inv.getAttachment("metric.metricB"));
  }

  // ==================== wrapWithHistogram ====================

  @Test
  void histogram_recordsElapsedMs_whenStartPresent_nameFallsBackToValue() {
    Interceptor interceptor = interceptorFor("histogramA");
    MethodInvocation inv = newInvocation();

    interceptor.before(inv);
    interceptor.after(inv);
    assertNotNull(inv.getAttachment("histogram.histogramA.elapsedMs"));
  }

  @Test
  void histogram_skipsRecording_whenStartAbsent_customName() {
    Interceptor interceptor = interceptorFor("histogramB");
    MethodInvocation inv = newInvocation();
    inv.setThrowable(new RuntimeException("x"));

    interceptor.onException(inv);
    assertNull(inv.getAttachment("histogram.customHist.elapsedMs"));
  }

  // ==================== wrapWithGauge ====================

  @Test
  void gauge_argIndexBranch_recordsNumberArgAndSkipsNonNumber() {
    Interceptor interceptor = interceptorFor("gaugeA");

    MethodInvocation numericInv = newInvocation(7);
    interceptor.before(numericInv);
    interceptor.after(numericInv);
    assertEquals(7.0, numericInv.getAttachment("gauge.gaugeA"));

    MethodInvocation nonNumericInv = newInvocation("str");
    interceptor.after(nonNumericInv);
    assertNull(nonNumericInv.getAttachment("gauge.gaugeA"));

    assertDoesNotThrow(() -> interceptor.onException(numericInv));
  }

  @Test
  void gauge_useReturnBranch_recordsNumberReturnAndSkipsNonNumber() {
    Interceptor interceptor = interceptorFor("gaugeB");

    MethodInvocation numericInv = newInvocation();
    numericInv.initReturnValue(3.14);
    interceptor.after(numericInv);
    assertEquals(3.14, numericInv.getAttachment("gauge.gaugeB"));

    MethodInvocation nonNumericInv = newInvocation();
    nonNumericInv.initReturnValue("nonnum");
    interceptor.after(nonNumericInv);
    assertNull(nonNumericInv.getAttachment("gauge.gaugeB"));
  }

  @Test
  void gauge_neitherArgIndexNorUseReturn_recordsNothing() {
    Interceptor interceptor = interceptorFor("gaugeC");
    MethodInvocation inv = newInvocation();

    interceptor.after(inv);
    assertNull(inv.getAttachment("gauge.gaugeC"));
  }
}
