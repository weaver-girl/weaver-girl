package com.example.account;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.metrics.MetricSnapshot;

/**
 * Exercises an account record and an export job under the account-guard plugin.
 *
 * <p>Neither {@link Account} nor {@link ExportJob} references the plugin. Run the program on its
 * own and the password comes back in clear text while the export throws; run it under the agent
 * with the plugin jar in {@code plugins/} and the password is masked, an empty write is rejected,
 * and a failed export comes back as a degraded result.
 *
 * <pre>
 * java -javaagent:weaver-girl-agent.jar=plugins=plugins \
 *      -cp weaver-girl-sample.jar com.example.account.AccountGuardDemo
 * </pre>
 *
 * <p>Exit code: 0 when the plugin changed the observable behaviour, 2 when it did not.
 */
public final class AccountGuardDemo {

  private static final String MASKED_READS = "account.password.reads";

  private static final String REJECTED_WRITES = "account.password.rejected";

  private static final String DEGRADED = "export.degraded";

  private AccountGuardDemo() {}

  public static void main(String[] args) {
    Account account = new Account("alice", "hunter2");

    System.out.println("--- reading the credential through the application's own code ---");
    String described = account.describe();
    System.out.println("describe() -> " + described);

    System.out.println();
    System.out.println("--- writing an empty password ---");
    account.setPassword("");
    String afterEmptyWrite = account.describe();
    System.out.println("after setPassword(\"\") -> " + afterEmptyWrite);

    System.out.println();
    System.out.println("--- an export on a provider that is down ---");
    String exportResult;
    try {
      exportResult = new ExportJob(false).run();
      System.out.println("run() -> " + exportResult);
    } catch (RuntimeException thrown) {
      exportResult = null;
      System.out.println("run() threw: " + thrown.getMessage());
    }

    long maskedReads = sum(MASKED_READS);
    long rejectedWrites = sum(REJECTED_WRITES);
    long degraded = sum(DEGRADED);

    System.out.println();
    System.out.println("what the plugin did:");
    System.out.println("  password reads masked : " + maskedReads);
    System.out.println("  empty writes rejected : " + rejectedWrites);
    System.out.println("  exports degraded      : " + degraded);

    boolean masked = described.endsWith("/********");
    boolean writeRejected = afterEmptyWrite.endsWith("/********");
    boolean exportDegraded = exportResult != null && exportResult.startsWith("unavailable");

    System.out.println();
    if (masked && writeRejected && exportDegraded) {
      System.out.println(
          "plugin active: the credential stayed masked, the empty write was rejected, and the"
              + " failed export came back degraded");
      System.exit(0);
    }
    System.out.println(
        "plugin not active: run this under the agent with the plugin jar in plugins/");
    System.exit(2);
  }

  private static long sum(String metric) {
    MetricSnapshot snapshot = MetricRegistry.snapshot(metric);
    return snapshot == null ? 0 : (long) snapshot.getSum();
  }

  /** Only used so a reader can see how many events the plugin published. */
  static int eventCount() {
    int count = 0;
    for (InterceptorEvent event : InterceptorEventPublisher.getInstance().getHistory()) {
      if ("account-guard".equals(event.getType())) {
        count++;
      }
    }
    return count;
  }
}
