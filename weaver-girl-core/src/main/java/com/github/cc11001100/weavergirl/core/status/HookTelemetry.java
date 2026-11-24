package com.github.cc11001100.weavergirl.core.status;

import com.github.cc11001100.weavergirl.api.config.PerformanceSwitches;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;

/**
 * Process-wide switch and recorder for the per-hook telemetry the advices perform around every
 * callback: the two {@link System#nanoTime()} reads, the per-hook metrics recording and the
 * plugin-level metrics mirror.
 *
 * <p>Telemetry is observation only — it never changes whether a hook runs or what a hook does.
 * On a throughput-sensitive deployment that does not consume the agent's /stats or Prometheus
 * output, it is pure overhead on the hot path: measured on the reference machine, a single hook
 * spends roughly 20 ns in clock reads plus ~15 ns + ~9 ns in the two metric recorders, per
 * callback. This switch removes that whole block and keeps dispatch, the circuit-breaker check and
 * the hook invocation. Measured end to end in {@code InstrumentationOverheadBenchmark}, one hook
 * costs roughly a third as much with it off as with it on (see
 * {@code docs/benchmarks/baseline-v1.md}).
 *
 * <p>The circuit breaker's <em>failure</em> tripping is unaffected: a hook that throws is still
 * counted and still trips, because that path passes {@code -1} as the duration (unknown) and the
 * breaker only uses duration for slow-call detection.
 *
 * <p>The state lives in {@link PerformanceSwitches}, so this switch, the return-snapshot switch,
 * the MDC bridge, the plugin-metrics mirror and the circuit breaker can all be configured from
 * one place: {@code -Dweavergirl.telemetry.enabled=false}, the {@code telemetry=false} agent
 * argument, a YAML key, or {@link #setEnabled(boolean)} at runtime.
 *
 * <p>All advices (method, constructor, static-init, field, catch, argument-rewrite) record through
 * {@link #record(String, boolean, long)} so a deployment that switches telemetry off stops paying
 * for it everywhere, not just on the method path.
 *
 * @since 1.0.0
 */
public final class HookTelemetry {

  private HookTelemetry() {}

  /** @return whether the per-hook telemetry (timing and metrics) should be recorded */
  public static boolean isEnabled() {
    return PerformanceSwitches.isTelemetryEnabled();
  }

  /** Turn the telemetry on or off. */
  public static void setEnabled(boolean value) {
    PerformanceSwitches.setEnabled(PerformanceSwitches.TELEMETRY, value);
  }

  /**
   * Take the pre-callback timestamp, or {@code 0} when telemetry is off so the clock read is
   * skipped as well.
   *
   * @return {@code nanoTime()} when telemetry is enabled, otherwise {@code 0}
   */
  public static long startNanos() {
    return isEnabled() ? System.nanoTime() : 0L;
  }

  /**
   * Record one hook callback's outcome and duration.
   *
   * <p>A zero {@code startNanos} means the clock was not read, because telemetry was off when the
   * callback began. Deciding on that value rather than on the current switch state matters: if the
   * switch flipped to on mid-callback, timing against 0 would produce an enormous duration and
   * spuriously trip the slow-call breaker.
   *
   * @param name the interceptor definition name
   * @param success whether the callback completed without throwing
   * @param startNanos the value {@link #startNanos()} returned before the callback
   */
  public static void record(String name, boolean success, long startNanos) {
    if (startNanos == 0L) {
      // Failures still matter to the breaker; successes without telemetry are pure bookkeeping.
      if (!success) {
        InterceptorHolder.recordOutcome(name, false, -1L);
      }
      return;
    }
    long hookNanos = System.nanoTime() - startNanos;
    if (hookNanos < 0) {
      hookNanos = 0;
    }
    InterceptorHolder.recordOutcome(name, success, hookNanos);
    AgentStatus.getInstance().recordInterceptorInvocation(name, success, hookNanos);
  }
}
