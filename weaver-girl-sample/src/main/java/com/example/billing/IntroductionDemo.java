package com.example.billing;

import com.github.cc11001100.weavergirl.api.plugin.PluginServices;

/**
 * Asks an invoice whether it can already render itself as CSV.
 *
 * <p>{@link Invoice} has no CSV support and never mentions the plugin. On its own the demo prints
 * "not an InvoiceCsv". Under the agent with the {@code csv-export} plugin jar in {@code plugins/}
 * the same object answers the interface and produces a real row.
 *
 * <pre>
 * java -javaagent:weaver-girl-agent.jar=plugins=plugins \
 *      -cp weaver-girl-sample.jar com.example.billing.IntroductionDemo
 * </pre>
 *
 * <p>Exit code: 0 when the class was given the interface and answered through the delegate, 2 when
 * it was not.
 */
public final class IntroductionDemo {

  private IntroductionDemo() {}

  public static void main(String[] args) {
    Invoice invoice = new Invoice("alice", 2599);

    System.out.println("--- asking the invoice whether it is an InvoiceCsv ---");
    boolean added = invoice instanceof InvoiceCsv;
    System.out.println(
        "interfaces=" + java.util.Arrays.toString(invoice.getClass().getInterfaces()));
    System.out.println("instanceof InvoiceCsv -> " + added);

    String row = null;
    if (added) {
      row = toCsvRow(invoice);
      System.out.println("toCsvRow() -> " + row);
    }

    System.out.println();
    if (added && "alice,2599".equals(row)) {
      System.out.println(
          "plugin active: the invoice was given the InvoiceCsv interface and answered through the"
              + " plugin's delegate");
      System.exit(0);
    }
    System.out.println(
        "plugin not active: run this under the agent with the csv-export plugin jar in plugins/");
    System.exit(2);
  }

  /**
   * Answers the CSV interface on an object that does not declare it.
   *
   * <p>The parameter is typed {@code Object} deliberately: statically {@link Invoice} never
   * implements {@link InvoiceCsv}, so a cast at the call site is provably impossible and SpotBugs
   * reports it (BC_IMPOSSIBLE_CAST). Here the type is unknown, which is the honest description of
   * what happens — the agent added the interface at runtime, and the caller has already proved it
   * with {@code instanceof}.
   */
  private static String toCsvRow(Object target) {
    return ((InvoiceCsv) target).toCsvRow();
  }

  /** Kept so the class compiles against the plugin-service API a product would also use. */
  static int serviceCount() {
    return PluginServices.get(Runnable.class).size();
  }
}
