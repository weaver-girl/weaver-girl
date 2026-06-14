package com.github.cc11001100.weavergirl.core.config;

import com.github.cc11001100.weavergirl.api.alert.AlertEvent;
import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import com.github.cc11001100.weavergirl.core.alert.DefaultAlertEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlertConfigIntegrationTest {

    @Test
    void alertRuleConfig_toAlertRule_convertsCorrectly() {
        WeaverConfig.AlertRuleConfig config = new WeaverConfig.AlertRuleConfig();
        config.setName("high-cpu");
        config.setType("slow_call");
        config.setMetric("cpu_usage");
        config.setOperator("gte");
        config.setThreshold(80.0);
        config.setSeverity("critical");
        config.setMessage("CPU usage too high");

        AlertRule rule = config.toAlertRule();
        assertEquals("high-cpu", rule.getName());
        assertEquals("slow_call", rule.getType());
        assertEquals("cpu_usage", rule.getMetric());
        assertEquals("gte", rule.getOperator());
        assertEquals(80.0, rule.getThreshold(), 0.01);
        assertEquals("critical", rule.getSeverity());
        assertEquals("CPU usage too high", rule.getMessage());
        assertTrue(rule.isEnabled());
    }

    @Test
    void alertRuleConfig_defaults_areCorrect() {
        WeaverConfig.AlertRuleConfig config = new WeaverConfig.AlertRuleConfig();
        config.setName("test-rule");
        config.setMetric("test-metric");

        AlertRule rule = config.toAlertRule();
        assertEquals("custom", rule.getType());
        assertEquals("gt", rule.getOperator());
        assertEquals(60000, rule.getWindowMs());
        assertEquals("warning", rule.getSeverity());
    }

    @Test
    void weaverConfig_alertRules_defaultIsEmpty() {
        WeaverConfig config = new WeaverConfig();
        assertNotNull(config.getAlertRules());
        assertTrue(config.getAlertRules().isEmpty());
    }

    @Test
    void alertRuleConfigs_canBeRegisteredInEngine() {
        DefaultAlertEngine engine = new DefaultAlertEngine();

        WeaverConfig.AlertRuleConfig config1 = new WeaverConfig.AlertRuleConfig();
        config1.setName("high-cpu");
        config1.setMetric("cpu_usage");
        config1.setThreshold(80);

        WeaverConfig.AlertRuleConfig config2 = new WeaverConfig.AlertRuleConfig();
        config2.setName("high-memory");
        config2.setMetric("memory_usage");
        config2.setThreshold(90);
        config2.setSeverity("critical");

        engine.addRule(config1.toAlertRule());
        engine.addRule(config2.toAlertRule());

        assertEquals(2, engine.getRules().size());

        // Verify evaluation works
        java.util.List<AlertEvent> alerts = engine.evaluate("cpu_usage", 85);
        assertEquals(1, alerts.size());
        assertEquals("high-cpu", alerts.get(0).getRuleName());
    }
}