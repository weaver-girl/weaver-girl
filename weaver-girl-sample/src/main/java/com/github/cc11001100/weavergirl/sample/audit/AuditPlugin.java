package com.github.cc11001100.weavergirl.sample.audit;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.exporter.DataExporter;
import com.github.cc11001100.weavergirl.api.exporter.ExporterRegistry;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * A drop-in audit plugin for {@code com.example.payments.PaymentService}.
 *
 * <p>This is the shape a third-party product takes. It depends on {@code weaver-girl-api} only, is
 * discovered through the {@code WeaverPlugin} service file, and is loaded because its jar sits in
 * the {@code plugins/} directory — the payment service never references it. It does three things
 * with the platform:
 *
 * <ul>
 *   <li>records an audit line for every charge and refund, with the card number masked
 *   <li>counts charged cents in {@link MetricRegistry}, which is what a product reads back
 *   <li>publishes each audit line as an event and ships it through its own {@link DataExporter}
 * </ul>
 *
 * <p>The trail is written to the audit log and to the platform's metric registry rather than held in
 * fields here. The plugin is loaded by its own class loader, so static state in this class would be
 * invisible to the application anyway.
 */
public class AuditPlugin extends AbstractPlugin {

  /**
   * The class this plugin instruments, named rather than referenced. A drop-in plugin must not
   * depend on the application it watches, so it matches by name and never imports the target.
   */
  static final String TARGET = "com.example.payments.PaymentService";

  static final String METRIC = "audit.charged.cents";

  private boolean enabled = true;

  @Override
  public String name() {
    return "payment-audit";
  }

  @Override
  public void init(PluginContext context) {
    enabled = context.getConfigBoolean("enabled", true);
    context.getLogger().info("payment audit plugin initialised, enabled=" + enabled);
    ExporterRegistry.register(new AuditExporter());
    ExporterRegistry.activate("payment-audit-log");
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    return enabled;
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    registry.register(
        intercept(TARGET)
            .method("charge")
            .around(
                inv -> inv.setAttachment("startedNs", System.nanoTime()),
                inv -> record(inv, "charge"))
            .build());
    registry.register(
        intercept(TARGET)
            .method("refund")
            .around(
                inv -> inv.setAttachment("startedNs", System.nanoTime()),
                inv -> record(inv, "refund"))
            .build());
  }

  private void record(
      com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation inv, String action) {
    // after() also runs when the method threw. A charge the service rejected never happened, so it
    // does not belong in the audit trail or the charged-cents total.
    if (inv.getThrowable() != null) {
      return;
    }
    Long started = (Long) inv.getAttachment("startedNs");
    long elapsedMs = started == null ? 0 : (System.nanoTime() - started) / 1_000_000;

    String line;
    if ("charge".equals(action)) {
      String card = mask(String.valueOf(inv.getArgument(0)));
      long cents = (Long) inv.getArgument(1);
      line = "charge card=" + card + " cents=" + cents + " in " + elapsedMs + "ms";
      MetricRegistry.record(METRIC, cents);
    } else {
      line = "refund payment=" + inv.getArgument(0) + " in " + elapsedMs + "ms";
    }
    // The audit line goes to the log. The application never sees this class, so the log is where a
    // reviewer reads the trail and the event is where the product's exporter picks it up.
    System.out.println("[payment-audit] " + line);

    InterceptorEventPublisher.getInstance()
        .publish(
            InterceptorEvent.builder()
                .type("payment-audit")
                .plugin(name())
                .className("PaymentService")
                .methodName(action)
                .durationMs(elapsedMs)
                .attribute("line", line)
                .build());
  }

  /** Keeps the first and last four digits, which is all an audit trail should retain. */
  static String mask(String cardNumber) {
    if (cardNumber == null || cardNumber.length() < 8) {
      return "****";
    }
    return cardNumber.substring(0, 4) + "********" + cardNumber.substring(cardNumber.length() - 4);
  }

  /**
   * Where the audit events go. A real product would ship these to its backend; this one writes them
   * to the audit log so the trail is visible without the application knowing the plugin exists.
   */
  static final class AuditExporter implements DataExporter {

    @Override
    public String name() {
      return "payment-audit-log";
    }

    @Override
    public void export(InterceptorEvent event) {
      if ("payment-audit".equals(event.getType())) {
        System.out.println("[payment-audit] exported " + event.getAttributes().get("line"));
      }
    }
  }
}
