package com.example.support;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.metrics.MetricSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * Files two support tickets and prints what an agent would paste into chat.
 *
 * <p>The store never references a plugin. On its own, {@code render} returns the raw message, so a
 * card number and a phone number leave the process. Run it under the agent with the redaction
 * plugin jar in {@code plugins/} and the same call comes back masked, because the plugin rewrote the
 * return value rather than being called:
 *
 * <pre>
 * java -javaagent:weaver-girl-agent.jar=plugins=plugins \
 *      -cp weaver-girl-sample.jar com.example.support.RedactionDemo
 * </pre>
 */
public final class RedactionDemo {

  /** The event type the ticket-redaction plugin publishes. */
  private static final String REDACTION_EVENT = "ticket-redaction";

  /** The metric the plugin counts masked secrets under. */
  private static final String REDACTION_METRIC = "redaction.secrets.masked";

  private RedactionDemo() {}

  public static void main(String[] args) {
    TicketStore desk = new TicketStore();

    TicketStore.Ticket card =
        desk.file("alice", "my card 4111111111111234 was charged twice, please refund");
    TicketStore.Ticket phone =
        desk.file("bob", "call me back on 13812345678 about order 9001");

    String renderedCard = desk.render(card.id);
    String renderedPhone = desk.render(phone.id);

    System.out.println("what the agent would paste into chat:");
    System.out.println("  ticket " + card.id + ": " + renderedCard);
    System.out.println("  ticket " + phone.id + ": " + renderedPhone);

    List<String> findings = new ArrayList<>();
    for (InterceptorEvent event : InterceptorEventPublisher.getInstance().getHistory()) {
      if (REDACTION_EVENT.equals(event.getType())) {
        findings.add(event.getAttributes().get("line"));
      }
    }
    MetricSnapshot masked = MetricRegistry.snapshot(REDACTION_METRIC);
    long maskedCount = masked == null ? 0 : masked.getCount();

    System.out.println();
    System.out.println("secrets the plugin caught (" + findings.size() + "):");
    for (String line : findings) {
      System.out.println("  " + line);
    }
    System.out.println("secrets masked, from the metric registry: " + maskedCount);

    // The store itself is untouched: it still holds the raw message. Only the text handed out was
    // rewritten, which is the difference between redacting a copy and editing the customer's ticket.
    boolean leaked =
        renderedCard.contains("4111111111111234") || renderedPhone.contains("13812345678");
    boolean pluginRan =
        !leaked
            && renderedCard.contains("411111******1234")
            && renderedPhone.contains("138****5678")
            && findings.size() == 2
            && maskedCount == 2
            && card.message.contains("4111111111111234");

    System.out.println();
    System.out.println(
        pluginRan
            ? "plugin active: the pasted text was redacted without the desk knowing"
            : "plugin not active: the raw card number and phone number left the process");
    if (!pluginRan) {
      System.exit(2);
    }
  }
}
