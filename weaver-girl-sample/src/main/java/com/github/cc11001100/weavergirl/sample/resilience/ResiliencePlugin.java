package com.github.cc11001100.weavergirl.sample.resilience;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Adds a circuit breaker to a payment gateway that has none.
 *
 * <p>The gateway forwards every charge and lets every failure escape. This plugin is loaded only
 * because its jar sits in {@code plugins/}; the gateway never references it. Once {@code
 * failureThreshold} charges in a row have failed, the circuit opens and the following charge is
 * answered with a queued fallback without ever calling the provider. The charge after that is let
 * through as a probe, and a success closes the circuit again.
 *
 * <p>The decision has to be made before the call. {@link MethodInvocation#proceed()} only marks the
 * chain as continuing; the gateway method itself runs after this callback returns, so its exception
 * is not visible here — it arrives on the exit path, which is too late to stop the call.
 */
public class ResiliencePlugin extends AbstractPlugin {

  /**
   * The class this plugin instruments, named rather than referenced. A drop-in plugin must not
   * depend on the application it watches.
   */
  static final String TARGET = "com.example.gateway.PaymentGateway";

  static final String METRIC = "resilience.charges.shed";

  /** Consecutive failures that open the circuit. */
  private final int failureThreshold;

  private int consecutiveFailures;
  private boolean open;

  public ResiliencePlugin() {
    this(3);
  }

  ResiliencePlugin(int failureThreshold) {
    this.failureThreshold = failureThreshold;
  }

  @Override
  public String name() {
    return "gateway-resilience";
  }

  @Override
  public void init(PluginContext context) {
    context.getLogger().info("gateway resilience plugin initialised, threshold=" + failureThreshold);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // One interceptor, not three. Each definition weaves the advice into the method again, so three
    // of them would run the gateway three times per charge. The around callback decides whether this
    // call reaches the provider; the exit callbacks see how it ended. An attachment bridges the two,
    // because the exit advice gets its own invocation and would otherwise not know the call was shed.
    registry.register(
        intercept(TARGET)
            .method("charge")
            .around(this::guard, this::recordSuccess, this::recordFailure)
            .build());
  }

  private void guard(MethodInvocation invocation) {
    long cents = (Long) invocation.getArgument(0);
    if (open) {
      // The provider has failed enough times in a row. Answer here and spend no call on it. The
      // circuit sheds exactly one charge, then lets the next one through to see whether the
      // provider has recovered.
      open = false;
      consecutiveFailures = 0;
      invocation.setReturnValue("queued:" + cents);
      note(invocation, "shed charge of " + cents + " cents, circuit open");
      MetricRegistry.record(METRIC, 1);
      return;
    }
    invocation.proceed();
  }

  private void recordFailure(MethodInvocation invocation) {
    long cents = (Long) invocation.getArgument(0);
    consecutiveFailures++;
    if (consecutiveFailures >= failureThreshold) {
      open = true;
    }
    note(
        invocation,
        "charge of "
            + cents
            + " cents failed ("
            + consecutiveFailures
            + " in a row)"
            + (open ? ", circuit opened" : ""));
  }

  private void recordSuccess(MethodInvocation invocation) {
    // A shed charge never ran, but the exit advice still fires and would count it as a success.
    // The fallback value is how the two are told apart: the provider only ever returns "approved:".
    Object returned = invocation.getReturnValue();
    if (returned instanceof String && ((String) returned).startsWith("queued:")) {
      return;
    }
    long cents = (Long) invocation.getArgument(0);
    boolean recovered = consecutiveFailures > 0;
    consecutiveFailures = 0;
    open = false;
    note(
        invocation,
        "charge of " + cents + " cents approved" + (recovered ? ", circuit closed" : ""));
  }

  private void note(MethodInvocation invocation, String line) {
    System.out.println("[gateway-resilience] " + line);
    InterceptorEventPublisher.getInstance()
        .publish(
            InterceptorEvent.builder()
                .type("gateway-resilience")
                .plugin(name())
                .className("PaymentGateway")
                .methodName("charge")
                .attribute("line", line)
                .build());
  }
}
