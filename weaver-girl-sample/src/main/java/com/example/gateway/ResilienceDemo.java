package com.example.gateway;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.metrics.MetricSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends six charges at a provider that fails its first four calls.
 *
 * <p>The gateway never references a plugin. On its own, all four failures reach the caller and all
 * six charges hit the provider. Run it under the agent with the resilience plugin jar in {@code
 * plugins/} and the plugin opens the circuit after three failures, so the fourth charge is answered
 * from a fallback without ever calling the provider, and the circuit closes again once the provider
 * recovers:
 *
 * <pre>
 * java -javaagent:weaver-girl-agent.jar=plugins=plugins \
 *      -cp weaver-girl-sample.jar com.example.gateway.ResilienceDemo
 * </pre>
 */
public final class ResilienceDemo {

  /** The event type the gateway-resilience plugin publishes. */
  private static final String RESILIENCE_EVENT = "gateway-resilience";

  /** The metric the plugin counts shed charges under. */
  private static final String SHED_METRIC = "resilience.charges.shed";

  private ResilienceDemo() {}

  public static void main(String[] args) {
    Upstream provider = new Upstream(4);
    PaymentGateway gateway = new PaymentGateway(provider);

    int served = 0;
    int failed = 0;
    int shed = 0;
    System.out.println("charges:");
    for (int i = 1; i <= 6; i++) {
      try {
        String result = gateway.charge(100L * i);
        if (result.startsWith("queued:")) {
          shed++;
          System.out.println("  charge " + i + " -> " + result + " (answered without the provider)");
        } else {
          served++;
          System.out.println("  charge " + i + " -> " + result);
        }
      } catch (IllegalStateException down) {
        failed++;
        System.out.println("  charge " + i + " -> failed: " + down.getMessage());
      }
    }

    List<String> lines = new ArrayList<>();
    for (InterceptorEvent event : InterceptorEventPublisher.getInstance().getHistory()) {
      if (RESILIENCE_EVENT.equals(event.getType())) {
        lines.add(event.getAttributes().get("line"));
      }
    }
    MetricSnapshot shedMetric = MetricRegistry.snapshot(SHED_METRIC);
    long shedRecorded = shedMetric == null ? 0 : shedMetric.getCount();

    System.out.println();
    System.out.println(
        "served by the provider: "
            + served
            + ", failed: "
            + failed
            + ", shed by the plugin: "
            + shed);
    System.out.println("the provider was actually called " + provider.calls() + " time(s)");
    System.out.println();
    System.out.println("what the plugin decided (" + lines.size() + "):");
    for (String line : lines) {
      System.out.println("  " + line);
    }
    System.out.println("charges shed, from the metric registry: " + shedRecorded);

    // The provider fails its first four calls. The circuit opens on the third failure, so the fourth
    // charge is answered from the fallback and never reaches the provider. The fifth is let through
    // and still fails, and the sixth finds the provider recovered.
    boolean pluginRan =
        served == 1
            && failed == 4
            && shed == 1
            && provider.calls() == 5
            && lines.size() == 6
            && shedRecorded == 1;

    System.out.println();
    System.out.println(
        pluginRan
            ? "plugin active: the circuit breaker shed a charge the gateway would have sent"
            : "plugin not active: every charge hit the provider and every failure reached the caller");
    if (!pluginRan) {
      System.exit(2);
    }
  }
}
