package com.github.cc11001100.weavergirl.sample.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.exporter.ExporterRegistry;
import com.github.cc11001100.weavergirl.core.plugin.DefaultPluginContext;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;

/** The audit plugin registers its exporter on the engine context and loses it when that context goes. */
class AuditPluginContextExportTest {

  @RegisterExtension
  static final TestWatcher REPORTER =
      new TestWatcher() {
        @Override
        public void testSuccessful(ExtensionContext context) {
          System.out.println("PASSED " + context.getDisplayName());
        }
      };

  @AfterEach
  void reset() {
    ExporterRegistry.shutdownAll();
  }

  @Test
  @DisplayName("audit plugin exporter receives through the engine context until that context is withdrawn")
  void auditExporterReceivesUntilTheContextIsWithdrawn() {
    DefaultPluginContext context =
        new DefaultPluginContext(
            new DefaultInterceptorRegistry(), Collections.<String, String>emptyMap(), "payment-audit");
    new AuditPlugin().init(context);
    assertTrue(
        ExporterRegistry.getActiveExporterNames().contains("payment-audit-log"),
        "context registration did not activate the audit exporter");

    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured));
    try {
      InterceptorEventPublisher.getInstance()
          .publish(
              InterceptorEvent.builder()
                  .type("payment-audit")
                  .plugin("payment-audit")
                  .className("PaymentService")
                  .methodName("charge")
                  .attribute("line", "charge card=**** cents=1")
                  .build());
      String first = captured.toString();
      assertTrue(first.contains("[payment-audit] exported charge card=**** cents=1"), first);

      context.withdrawServices();
      InterceptorEventPublisher.getInstance()
          .publish(
              InterceptorEvent.builder()
                  .type("payment-audit")
                  .plugin("payment-audit")
                  .className("PaymentService")
                  .methodName("refund")
                  .attribute("line", "refund payment=p1")
                  .build());
      assertFalse(captured.toString().contains("refund payment=p1"), captured.toString());
      assertNull(ExporterRegistry.get("payment-audit-log"));
    } finally {
      System.setOut(original);
      context.withdrawServices();
      ExporterRegistry.shutdownAll();
    }
  }
}
