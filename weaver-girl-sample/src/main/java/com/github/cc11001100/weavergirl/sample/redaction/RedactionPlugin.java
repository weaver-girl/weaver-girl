package com.github.cc11001100.weavergirl.sample.redaction;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stops a support desk from pasting raw card numbers and phone numbers into chat.
 *
 * <p>The desk stores the customer's message verbatim and returns it from {@code render}. This plugin
 * is loaded only because its jar sits in {@code plugins/}; the desk never references it. After
 * {@code render} returns, the plugin rewrites the return value so the caller receives masked text,
 * counts each secret it caught, and publishes a finding. The stored ticket is left alone.
 */
public class RedactionPlugin extends AbstractPlugin {

  /**
   * The class this plugin instruments, named rather than referenced. A drop-in plugin must not
   * depend on the application it watches.
   */
  static final String TARGET = "com.example.support.TicketStore";

  static final String METRIC = "redaction.secrets.masked";

  /**
   * A card number: 13 to 19 digits, with the separators between groups kept in the match so masking
   * does not glue the number to the word that follows it.
   */
  private static final Pattern CARD =
      Pattern.compile("(?<!\\d)\\d(?:[ -]?\\d){12,18}(?!\\d)");

  /** A mainland mobile number. */
  private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

  @Override
  public String name() {
    return "ticket-redaction";
  }

  @Override
  public void init(PluginContext context) {
    context.getLogger().info("ticket redaction plugin initialised");
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    registry.register(
        intercept(TARGET).method("render").after(RedactionPlugin::redact).build());
  }

  private static void redact(MethodInvocation invocation) {
    Object returned = invocation.getReturnValue();
    if (!(returned instanceof String) || invocation.getThrowable() != null) {
      return;
    }
    String original = (String) returned;
    String masked = maskPhones(maskCards(original));
    if (masked.equals(original)) {
      return;
    }
    invocation.setReturnValue(masked);

    int ticketId = (Integer) invocation.getArgument(0);
    int secrets = countOccurrences(original);
    MetricRegistry.record(METRIC, secrets);
    String line = "ticket " + ticketId + ": masked " + secrets + " secret(s) before it left the desk";
    System.out.println("[ticket-redaction] " + line);

    InterceptorEventPublisher.getInstance()
        .publish(
            InterceptorEvent.builder()
                .type("ticket-redaction")
                .plugin("ticket-redaction")
                .className("TicketStore")
                .methodName("render")
                .attribute("line", line)
                .build());
  }

  /** Keeps the first six and last four digits, which is what a card receipt already shows. */
  static String maskCards(String text) {
    Matcher matcher = CARD.matcher(text);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      String digits = matcher.group().replaceAll("[^0-9]", "");
      String masked = digits.substring(0, 6) + "******" + digits.substring(digits.length() - 4);
      matcher.appendReplacement(out, Matcher.quoteReplacement(masked));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  /** Keeps the first three and last four digits of a mobile number. */
  static String maskPhones(String text) {
    Matcher matcher = PHONE.matcher(text);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      String digits = matcher.group();
      String masked = digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
      matcher.appendReplacement(out, Matcher.quoteReplacement(masked));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static int countOccurrences(String text) {
    return count(CARD, text) + count(PHONE, text);
  }

  private static int count(Pattern pattern, String text) {
    int found = 0;
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      found++;
    }
    return found;
  }
}
