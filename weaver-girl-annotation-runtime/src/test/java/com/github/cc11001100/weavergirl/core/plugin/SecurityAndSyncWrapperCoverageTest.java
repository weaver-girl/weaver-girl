package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.annotation.Audited;
import com.github.cc11001100.weavergirl.annotation.ReadOnly;
import com.github.cc11001100.weavergirl.annotation.RequiresRole;
import com.github.cc11001100.weavergirl.annotation.Synchronized;
import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the "security" and "concurrency" annotation-dimension wrapper methods in {@link
 * AnnotationPluginLoader}: {@code @RequiresRole}, {@code @Audited}, {@code @Synchronized}, and
 * {@code @ReadOnly}.
 *
 * <p>{@code AnnotationPluginLoader}'s static {@code lockObjects} map persists across tests, so
 * every {@code @Synchronized} target-method name and every target {@link Class} used below is
 * unique to this file to avoid cross-test pollution of that map.
 */
class SecurityAndSyncWrapperCoverageTest {

  // Dedicated marker classes so that the "lockKey empty -> falls back to target class name"
  // branch can be tested with a class whose lock has never been created (lock == null branch)
  // versus one whose lock was created via before() (lock != null branch).
  private static class FallbackLockPopulatedTarget {}

  private static class FallbackLockNeverPopulatedTarget {}

  @WeaveClass(target = "com.example.SecuritySyncService")
  public static class SecuritySyncInterceptor {
    static final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    @RequiresRole(value = "requiresRoleGuardedDelete", role = "ADMIN", principalArgIndex = 1)
    public void guardDelete(MethodInvocation inv) {
      calls.add("guardDelete");
    }

    @Audited(value = "auditedActionFallback", includeArgs = true, includeResult = true)
    public void auditActionFallback(MethodInvocation inv) {
      calls.add("auditActionFallback");
    }

    @Audited(
        value = "auditedExplicitAction",
        action = "TRANSFER",
        includeArgs = false,
        includeResult = false)
    public void auditExplicitAction(MethodInvocation inv) {
      calls.add("auditExplicitAction");
    }

    @Synchronized(value = "syncedFallbackLock")
    public void syncFallback(MethodInvocation inv) {
      calls.add("syncFallback");
    }

    @Synchronized(value = "syncedExplicitLock", lockKey = "explicit-lock-scw")
    public void syncExplicit(MethodInvocation inv) {
      calls.add("syncExplicit");
    }

    @ReadOnly("readOnlyFindById")
    public void guardReadOnly(MethodInvocation inv) {
      calls.add("guardReadOnly");
    }
  }

  private AnnotationPluginLoader loader;
  private InterceptorRegistry registry;

  @BeforeEach
  void setUp() {
    loader = new AnnotationPluginLoader();
    registry = new TestInterceptorRegistry();
    SecuritySyncInterceptor.calls.clear();
  }

  private void load() {
    Set<Class<?>> classes = new HashSet<>();
    classes.add(SecuritySyncInterceptor.class);
    loader.loadAnnotatedInterceptors(classes, registry);
  }

  private Interceptor interceptorFor(String methodName) {
    return registry.getAllDefinitions().stream()
        .filter(d -> d.getName().endsWith("-" + methodName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no definition for " + methodName))
        .getInterceptor();
  }

  // ==================== @RequiresRole ====================

  @Test
  void requiresRole_before_setsRequiredRoleAndPrincipalFromConfiguredArgIndex() {
    load();
    Interceptor interceptor = interceptorFor("requiresRoleGuardedDelete");
    MethodInvocation inv =
        new MethodInvocation(
            SecuritySyncInterceptor.class,
            "requiresRoleGuardedDelete",
            null,
            new Object[] {"ignoredArg0", "alice"});

    interceptor.before(inv);

    assertEquals("ADMIN", inv.getAttachment("requiresRole.required"));
    assertEquals("alice", inv.getAttachment("requiresRole.principal"));
    assertTrue(SecuritySyncInterceptor.calls.contains("guardDelete"));
  }

  @Test
  void requiresRole_afterAndOnException_justDelegate() {
    load();
    Interceptor interceptor = interceptorFor("requiresRoleGuardedDelete");
    MethodInvocation inv =
        new MethodInvocation(
            SecuritySyncInterceptor.class,
            "requiresRoleGuardedDelete",
            null,
            new Object[] {"ignoredArg0", "alice"});

    assertDoesNotThrow(() -> interceptor.after(inv));

    inv.setThrowable(new RuntimeException("boom"));
    assertDoesNotThrow(() -> interceptor.onException(inv));
  }

  // ==================== @Audited ====================

  @Test
  void audited_actionFallsBackToValue_andIncludeArgsTrue_setsArgsAttachment() {
    load();
    Interceptor interceptor = interceptorFor("auditedActionFallback");
    Object[] args = {"x", 1};
    MethodInvocation inv =
        new MethodInvocation(SecuritySyncInterceptor.class, "auditedActionFallback", null, args);

    interceptor.before(inv);

    assertEquals("auditedActionFallback", inv.getAttachment("audit.action"));
    assertNotNull(inv.getAttachment("audit.timestamp"));
    assertEquals(Arrays.toString(args), inv.getAttachment("audit.args"));
  }

  @Test
  void audited_explicitAction_andIncludeArgsFalse_doesNotSetArgsAttachment() {
    load();
    Interceptor interceptor = interceptorFor("auditedExplicitAction");
    MethodInvocation inv =
        new MethodInvocation(
            SecuritySyncInterceptor.class, "auditedExplicitAction", null, new Object[] {"y"});

    interceptor.before(inv);

    assertEquals("TRANSFER", inv.getAttachment("audit.action"));
    assertNull(inv.getAttachment("audit.args"));
  }

  @Test
  void audited_after_includeResultTrue_setsAuditResultAndInvokesGuardMethod() {
    load();
    Interceptor interceptor = interceptorFor("auditedActionFallback");
    MethodInvocation inv =
        new MethodInvocation(
            SecuritySyncInterceptor.class, "auditedActionFallback", null, new Object[] {"x", 1});
    inv.initReturnValue("resultValue");

    interceptor.after(inv);

    assertEquals("resultValue", inv.getAttachment("audit.result"));
    assertTrue(SecuritySyncInterceptor.calls.contains("auditActionFallback"));
  }

  @Test
  void audited_after_includeResultFalse_doesNotSetAuditResult() {
    load();
    Interceptor interceptor = interceptorFor("auditedExplicitAction");
    MethodInvocation inv =
        new MethodInvocation(
            SecuritySyncInterceptor.class, "auditedExplicitAction", null, new Object[] {"y"});
    inv.initReturnValue("resultValue");

    interceptor.after(inv);

    assertNull(inv.getAttachment("audit.result"));
  }

  @Test
  void audited_onException_setsAuditExceptionFromThrowableMessage() {
    load();
    Interceptor interceptor = interceptorFor("auditedActionFallback");
    MethodInvocation inv =
        new MethodInvocation(
            SecuritySyncInterceptor.class, "auditedActionFallback", null, new Object[] {"x", 1});
    inv.setThrowable(new RuntimeException("kaboom"));

    interceptor.onException(inv);

    assertEquals("kaboom", inv.getAttachment("audit.exception"));
    assertTrue(SecuritySyncInterceptor.calls.contains("auditActionFallback"));
  }

  // ==================== @Synchronized ====================

  @Test
  void synchronized_lockKeyEmpty_beforeThenAfter_hitsLockNotNullBranch() {
    load();
    Interceptor interceptor = interceptorFor("syncedFallbackLock");
    MethodInvocation inv =
        new MethodInvocation(
            FallbackLockPopulatedTarget.class, "syncedFallbackLock", null, new Object[0]);

    // before() lazily creates the lock for this (empty-lockKey -> target class name) key.
    interceptor.before(inv);
    // after() finds the lock created above -> lock != null branch.
    assertDoesNotThrow(() -> interceptor.after(inv));
  }

  @Test
  void synchronized_lockKeyEmpty_afterAndOnExceptionWithoutBefore_hitLockNullBranch() {
    load();
    Interceptor interceptor = interceptorFor("syncedFallbackLock");
    MethodInvocation inv =
        new MethodInvocation(
            FallbackLockNeverPopulatedTarget.class, "syncedFallbackLock", null, new Object[0]);

    // before() is intentionally never called for this target class, so lockObjects has no
    // entry for its name -- exercises the `lock == null` else-branch in after() and
    // onException().
    assertDoesNotThrow(() -> interceptor.after(inv));

    inv.setThrowable(new RuntimeException("x"));
    assertDoesNotThrow(() -> interceptor.onException(inv));
  }

  @Test
  void synchronized_explicitLockKey_beforeAfterOnException_usesKeyVerbatim_lockNotNullBranch() {
    load();
    Interceptor interceptor = interceptorFor("syncedExplicitLock");
    MethodInvocation inv =
        new MethodInvocation(
            SecuritySyncInterceptor.class, "syncedExplicitLock", null, new Object[0]);

    interceptor.before(inv);
    assertDoesNotThrow(() -> interceptor.after(inv));

    inv.setThrowable(new RuntimeException("x"));
    assertDoesNotThrow(() -> interceptor.onException(inv));
  }

  // ==================== @ReadOnly ====================

  @Test
  void readOnly_before_setsAttachmentAndInvokesGuardMethod() {
    load();
    Interceptor interceptor = interceptorFor("readOnlyFindById");
    MethodInvocation inv =
        new MethodInvocation(
            SecuritySyncInterceptor.class, "readOnlyFindById", null, new Object[0]);

    interceptor.before(inv);

    assertEquals(true, inv.getAttachment("readOnly"));
    assertTrue(SecuritySyncInterceptor.calls.contains("guardReadOnly"));
  }

  @Test
  void readOnly_afterAndOnException_justDelegate() {
    load();
    Interceptor interceptor = interceptorFor("readOnlyFindById");
    MethodInvocation inv =
        new MethodInvocation(
            SecuritySyncInterceptor.class, "readOnlyFindById", null, new Object[0]);

    assertDoesNotThrow(() -> interceptor.after(inv));

    inv.setThrowable(new RuntimeException("x"));
    assertDoesNotThrow(() -> interceptor.onException(inv));
  }
}
