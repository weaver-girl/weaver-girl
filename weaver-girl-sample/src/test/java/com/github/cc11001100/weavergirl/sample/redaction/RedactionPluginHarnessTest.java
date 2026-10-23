package com.github.cc11001100.weavergirl.sample.redaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.support.TicketStore;
import com.github.cc11001100.weavergirl.testkit.PluginTestHarness;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Tests the sample redaction plugin with the in-process harness instead of the agent.
 *
 * <p>The shipped demo launches the application twice, once with the jar in {@code plugins/} and once
 * without it. That proves loading and packaging, which is the expensive part. This test answers the
 * cheaper question for the product code itself: does the callback fire, and does its return-value
 * rewrite reach the caller?
 */
class RedactionPluginHarnessTest {

  @Test
  void renderReturnsMaskedTextWhileTheStoredTicketStaysIntact() {
    PluginTestHarness created;
    try {
      created = PluginTestHarness.create().install(new RedactionPlugin());
    } catch (IllegalStateException noAttach) {
      Assumptions.assumeTrue(false, "Instrumentation attach unavailable: " + noAttach.getMessage());
      return;
    }

    String raw = "card 4111 1111 1111 1111, call 13800138000";
    try (PluginTestHarness harness = created) {
      harness.weave(TicketStore.class);

      TicketStore store = new TicketStore();
      store.file("Ada", raw);

      assertEquals(
          "card 411111******1111, call 138****8000",
          store.render(1),
          "the caller sees the masked text");
      assertEquals(raw, store.history().get(0).message, "the stored ticket is untouched");
    }
  }
}
