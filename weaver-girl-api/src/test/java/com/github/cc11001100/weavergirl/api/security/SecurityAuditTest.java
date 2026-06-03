package com.github.cc11001100.weavergirl.api.security;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for security audit system (P55).
 */
class SecurityAuditTest {

    @AfterEach
    void tearDown() {
        SecurityAuditLog.clear();
        SecurityAuditLog.setPolicy(SecurityPolicy.builder().build());
    }

    // ===== SecurityPolicy =====

    @Test
    void policy_defaultAllowsAll() {
        SecurityPolicy policy = SecurityPolicy.builder().build();
        assertTrue(policy.isClassAllowed("com.example.Anything"));
        assertTrue(policy.isMethodAllowed("com.example.Anything", "doStuff"));
    }

    @Test
    void policy_denyTakesPrecedence() {
        SecurityPolicy policy = SecurityPolicy.builder()
                .allowPattern("com.example.*")
                .denyPattern("com.example.secret.*")
                .build();

        assertTrue(policy.isClassAllowed("com.example.Service"));
        assertFalse(policy.isClassAllowed("com.example.secret.Vault"));
    }

    @Test
    void policy_allowListRestrictsAccess() {
        SecurityPolicy policy = SecurityPolicy.builder()
                .allowPattern("com.example.service.*")
                .defaultAllow(false)
                .build();

        assertTrue(policy.isClassAllowed("com.example.service.UserService"));
        assertFalse(policy.isClassAllowed("com.other.Service"));
    }

    @Test
    void policy_wildcardPatterns() {
        SecurityPolicy policy = SecurityPolicy.builder()
                .allowPattern("com.example.*")
                .denyPattern("*Password")
                .denyPattern("*secret*")
                .build();

        assertTrue(policy.isClassAllowed("com.example.Service"));
        assertFalse(policy.isMethodAllowed("com.example.Service", "getPassword"));
        assertTrue(policy.isMethodAllowed("com.example.Service", "getName"));
    }

    @Test
    void policy_starMatchesAll() {
        SecurityPolicy policy = SecurityPolicy.builder()
                .denyPattern("*")
                .build();
        assertFalse(policy.isClassAllowed("anything"));
    }

    @Test
    void policy_auditAllFlag() {
        SecurityPolicy policy = SecurityPolicy.builder()
                .auditAllInterceptions(true)
                .build();
        assertTrue(policy.shouldAuditAll());
    }

    // ===== AuditRecord =====

    @Test
    void auditRecord_builder() {
        AuditRecord record = AuditRecord.builder()
                .operation("TEST_OP")
                .principal("test-user")
                .target("test-target")
                .result("SUCCESS")
                .detail("key1", "value1")
                .build();

        assertTrue(record.getTimestamp() > 0);
        assertEquals("TEST_OP", record.getOperation());
        assertEquals("test-user", record.getPrincipal());
        assertEquals("test-target", record.getTarget());
        assertEquals("SUCCESS", record.getResult());
        assertEquals("value1", record.getDetails().get("key1"));
    }

    @Test
    void auditRecord_detailsAreImmutable() {
        AuditRecord record = AuditRecord.builder()
                .operation("OP").target("T").result("R")
                .detail("k", "v").build();
        assertThrows(UnsupportedOperationException.class,
                () -> record.getDetails().put("new", "val"));
    }

    // ===== SecurityAuditLog =====

    @Test
    void auditLog_recordAndRetrieve() {
        SecurityAuditLog.record(AuditRecord.builder()
                .operation("TEST").target("t1").result("OK").build());
        SecurityAuditLog.record(AuditRecord.builder()
                .operation("TEST").target("t2").result("OK").build());

        assertEquals(2, SecurityAuditLog.size());
        List<AuditRecord> all = SecurityAuditLog.getRecords();
        assertEquals(2, all.size());
    }

    @Test
    void auditLog_filterByOperation() {
        SecurityAuditLog.record(AuditRecord.builder()
                .operation("INTERCEPT").target("t1").result("ALLOWED").build());
        SecurityAuditLog.record(AuditRecord.builder()
                .operation("CONFIG_CHANGE").target("k1").result("SUCCESS").build());

        List<AuditRecord> intercepted = SecurityAuditLog.getRecords("INTERCEPT");
        assertEquals(1, intercepted.size());
        assertEquals("INTERCEPT", intercepted.get(0).getOperation());
    }

    @Test
    void auditLog_recordInterception() {
        SecurityAuditLog.recordInterception("com.example.Service", "execute", true);
        assertEquals(1, SecurityAuditLog.size());
        assertEquals("INTERCEPT", SecurityAuditLog.getRecords().get(0).getOperation());
        assertEquals("ALLOWED", SecurityAuditLog.getRecords().get(0).getResult());
    }

    @Test
    void auditLog_recordConfigChange() {
        SecurityAuditLog.recordConfigChange("sampling.rate", "api", "1", "10");
        assertEquals(1, SecurityAuditLog.size());
        assertEquals("CONFIG_CHANGE", SecurityAuditLog.getRecords().get(0).getOperation());
    }

    @Test
    void auditLog_isInterceptionAllowed_withAudit() {
        SecurityAuditLog.setPolicy(SecurityPolicy.builder()
                .auditAllInterceptions(true)
                .build());

        assertTrue(SecurityAuditLog.isInterceptionAllowed("com.example.Svc", "run"));
        assertEquals(1, SecurityAuditLog.size());
    }

    @Test
    void auditLog_isInterceptionAllowed_denied() {
        SecurityAuditLog.setPolicy(SecurityPolicy.builder()
                .denyPattern("com.example.secret.*")
                .auditAllInterceptions(true)
                .build());

        assertFalse(SecurityAuditLog.isInterceptionAllowed("com.example.secret.Vault", "open"));
        List<AuditRecord> records = SecurityAuditLog.getRecords();
        assertEquals("DENIED", records.get(0).getResult());
    }

    @Test
    void auditLog_clear() {
        SecurityAuditLog.record(AuditRecord.builder()
                .operation("OP").target("T").result("R").build());
        SecurityAuditLog.clear();
        assertEquals(0, SecurityAuditLog.size());
    }

    @Test
    void auditLog_recordsAreBounded() {
        for (int i = 0; i < 600; i++) {
            SecurityAuditLog.record(AuditRecord.builder()
                    .operation("OP").target("t" + i).result("R").build());
        }
        assertTrue(SecurityAuditLog.size() <= 500, "Should be bounded at 500");
    }
}
