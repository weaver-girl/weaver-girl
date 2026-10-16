package com.example.payments;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.metrics.MetricSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the payment service and reports what the audit plugin made of it.
 *
 * <p>The service knows nothing about the plugin, and this class never names it either. It only reads
 * the platform's metric registry and event history, which a plugin and the application share. On its
 * own those stay empty. Run it under the agent with the plugin jar in {@code plugins/} and they fill
 * in, because the plugin was woven in rather than called:
 *
 * <pre>
 * java -javaagent:weaver-girl-agent.jar=plugins=plugins \
 *      -cp weaver-girl-sample.jar com.example.payments.AuditDemo
 * </pre>
 */
public final class AuditDemo {

  /** The metric the payment-audit plugin records charged cents under. */
  private static final String CHARGED_METRIC = "audit.charged.cents";

  /** The event type the payment-audit plugin publishes. */
  private static final String AUDIT_EVENT = "payment-audit";

  private AuditDemo() {}

  public static void main(String[] args) {
    PaymentService payments = new PaymentService();

    PaymentService.Payment first = payments.charge("4111111111111234", 2599);
    PaymentService.Payment second = payments.charge("5500000000009876", 750);
    payments.refund(first.id);
    try {
      payments.charge("4111111111111234", -1);
    } catch (IllegalArgumentException rejected) {
      System.out.println("service rejected a bad charge: " + rejected.getMessage());
    }

    System.out.println();
    System.out.println("payments the service recorded:");
    for (PaymentService.Payment payment : payments.history()) {
      System.out.println("  " + payment);
    }

    List<String> auditLines = new ArrayList<>();
    for (InterceptorEvent event : InterceptorEventPublisher.getInstance().getHistory()) {
      if (AUDIT_EVENT.equals(event.getType())) {
        auditLines.add(event.getAttributes().get("line"));
      }
    }
    MetricSnapshot charged = MetricRegistry.snapshot(CHARGED_METRIC);
    long chargedCents = charged == null ? 0 : (long) charged.getSum();

    System.out.println();
    System.out.println("audit events the plugin published (" + auditLines.size() + "):");
    for (String line : auditLines) {
      System.out.println("  " + line);
    }
    System.out.println("cents charged, from the metric registry: " + chargedCents);

    // Two charges were accepted, so two masked charge lines and one refund line, and the metric
    // sums exactly the cents the service took. The rejected charge never reached the plugin.
    boolean pluginRan = auditLines.size() == 3 && chargedCents == 2599 + 750;
    System.out.println();
    System.out.println(
        pluginRan
            ? "plugin active: the audit trail was produced without the service calling it"
            : "plugin not active: run this under the agent with the plugin jar in plugins/");
    if (!pluginRan) {
      System.exit(2);
    }
  }
}
