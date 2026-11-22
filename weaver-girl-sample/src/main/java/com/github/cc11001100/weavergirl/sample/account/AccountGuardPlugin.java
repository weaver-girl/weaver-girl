package com.github.cc11001100.weavergirl.sample.account;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.metrics.MetricRegistry;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Guards the account record with two join points the method hooks cannot reach.
 *
 * <p>It is loaded only because its jar sits in {@code plugins/}; {@code com.example.account} never
 * references it. Three things happen that the application does not do itself:
 *
 * <ul>
 *   <li><b>Field read</b> — every read of {@code password} comes back masked. The application's own
 *       {@code describe()} therefore prints a masked value even though it reads the field directly
 *       through the getter.
 *   <li><b>Field write</b> — an empty or whitespace-only password is rejected, so the field keeps
 *       the value it had. This is the one thing a read-only hook cannot do.
 *   <li><b>Failed exit</b> — an export that throws is answered with a degraded report instead of an
 *       exception, which is what the catch hook exists for.
 * </ul>
 *
 * <p>Both field callbacks matter, and they are separate: a write hook that only observes, or a
 * read hook that only logs, would each leave half the field unprotected.
 */
public class AccountGuardPlugin extends AbstractPlugin {

  /** Named rather than referenced: a drop-in plugin must not depend on the application it watches. */
  static final String ACCOUNT = "com.example.account.Account";

  static final String EXPORT_JOB = "com.example.account.ExportJob";

  static final String MASKED_READS = "account.password.reads";

  static final String REJECTED_WRITES = "account.password.rejected";

  static final String DEGRADED = "export.degraded";

  static final String MASK = "********";

  @Override
  public String name() {
    return "account-guard";
  }

  @Override
  public void init(PluginContext context) {
    context.getLogger().info("account guard plugin initialised");
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // Read hook: the value handed to the callback is the one actually read, so a guard can decide
    // per value instead of blindly masking everything. That depends on the field being read for the
    // callback — an earlier engine version passed null here and every guard saw "no value".
    registry.register(
        interceptField(ACCOUNT, "password")
            .onGet(AccountGuardPlugin::maskRead)
            .name("account-password-read")
            .build());

    // Write hook: reject an empty password. Restoring what should be kept is the hook's job — the
    // engine writes the value the advice puts back, so returning the previous one rejects the write.
    registry.register(
        interceptField(ACCOUNT, "password")
            .onSet(AccountGuardPlugin::guardWrite)
            .name("account-password-write")
            .build());

    // Catch hook: the job throws, its caller would see the exception. Answer with a degraded result.
    registry.register(
        interceptCatch(EXPORT_JOB, "java.lang.IllegalStateException")
            .onCatch(AccountGuardPlugin::degradeExport)
            .name("export-degrade")
            .build());
  }

  private static void maskRead(FieldInvocation invocation, Object originalValue) {
    if (originalValue == null) {
      return;
    }
    MetricRegistry.record(MASKED_READS, 1);
    invocation.setReturnValue(MASK);
    publish("read " + invocation.getFieldName() + " masked", "mask");
  }

  private static void guardWrite(FieldInvocation invocation, Object target, Object newValue) {
    if (newValue == null || String.valueOf(newValue).trim().isEmpty()) {
      MetricRegistry.record(REJECTED_WRITES, 1);
      publish("rejected empty " + invocation.getFieldName(), "reject");
      invocation.skipWrite();
      return;
    }
    publish("accepted " + invocation.getFieldName() + " of length " + String.valueOf(newValue).length(), "accept");
  }

  private static void degradeExport(
      com.github.cc11001100.weavergirl.api.interceptor.CatchInvocation invocation) {
    MetricRegistry.record(DEGRADED, 1);
    String reason = invocation.getCaughtException().getMessage();
    invocation.setCatchReturnValue("unavailable: " + reason);
    publish("export degraded: " + reason, "degrade");
  }

  private static void publish(String line, String action) {
    System.out.println("[account-guard] " + line);
    InterceptorEventPublisher.getInstance()
        .publish(
            InterceptorEvent.builder()
                .type("account-guard")
                .plugin("account-guard")
                .className("Account")
                .methodName(action)
                .attribute("line", line)
                .build());
  }

  /** Kept so a method hook can be added later without moving the class. */
  static void unused(MethodInvocation invocation) {}
}
