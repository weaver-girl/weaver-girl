package com.github.cc11001100.weavergirl.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.alert.AlertRule;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** Every {@link WeaverConfig} field round-trips, including the ones a config file leaves unset. */
class WeaverConfigTest {

  @Test
  void everyFieldRoundTrips() {
    WeaverConfig config = new WeaverConfig();
    config.setSamplingThreshold(5000);
    config.setCircuitBreakerFailures(3);
    config.setCircuitBreakerCooldown(30_000L);
    config.setExcludedClasses(Arrays.asList("com.example.Skip"));
    config.setLogLevel("DEBUG");
    config.setMaxTransformations(100);
    config.setOnlyInterceptPackages(Arrays.asList("com.example"));
    config.setDisabledPlugins(Arrays.asList("jdbc"));
    config.setEnabledPlugins(Arrays.asList("servlet"));
    config.setInterceptors(Collections.emptyList());

    assertEquals(5000, config.getSamplingThreshold());
    assertEquals(3, config.getCircuitBreakerFailures());
    assertEquals(30_000L, config.getCircuitBreakerCooldown());
    assertEquals(Arrays.asList("com.example.Skip"), config.getExcludedClasses());
    assertEquals("DEBUG", config.getLogLevel());
    assertEquals(100, config.getMaxTransformations());
    assertEquals(Arrays.asList("com.example"), config.getOnlyInterceptPackages());
    assertEquals(Arrays.asList("jdbc"), config.getDisabledPlugins());
    assertEquals(Arrays.asList("servlet"), config.getEnabledPlugins());
    assertTrue(config.getInterceptors().isEmpty());
  }

  /** A null interceptor list is stored as empty, so callers never see null. */
  @Test
  void aNullInterceptorListBecomesEmpty() {
    WeaverConfig config = new WeaverConfig();
    config.setInterceptors(null);
    assertTrue(config.getInterceptors().isEmpty());
  }

  /** Alert rules round-trip, and a null list reads back as empty. */
  @Test
  void alertRulesRoundTrip() {
    WeaverConfig config = new WeaverConfig();
    assertTrue(config.getAlertRules().isEmpty());

    WeaverConfig.AlertRuleConfig rule = new WeaverConfig.AlertRuleConfig();
    rule.setName("cpu");
    config.setAlertRules(Arrays.asList(rule));
    assertEquals("cpu", config.getAlertRules().get(0).getName());

    config.setAlertRules(null);
    assertTrue(config.getAlertRules().isEmpty());
  }

  /** An alert rule keeps every field it is given and converts to an engine rule. */
  @Test
  void anAlertRuleConvertsWithEveryField() {
    WeaverConfig.AlertRuleConfig rule = new WeaverConfig.AlertRuleConfig();
    rule.setName("cpu");
    rule.setType("threshold");
    rule.setMetric("cpu.usage");
    rule.setOperator("gte");
    rule.setThreshold(0.9);
    rule.setWindowMs(5_000);
    rule.setSeverity("critical");
    rule.setMessage("cpu high");
    rule.setEnabled(false);

    AlertRule converted = rule.toAlertRule();

    assertEquals("cpu", converted.getName());
    assertEquals("threshold", rule.getType());
    assertEquals("cpu.usage", rule.getMetric());
    assertEquals("gte", rule.getOperator());
    assertEquals(0.9, rule.getThreshold());
    assertEquals(5_000, rule.getWindowMs());
    assertEquals("critical", rule.getSeverity());
    assertEquals("cpu high", rule.getMessage());
    assertFalse(rule.isEnabled());
    assertFalse(converted.isEnabled());
  }

  /** An interceptor config keeps every matcher it is given. */
  @Test
  void anInterceptorConfigKeepsEveryMatcher() {
    WeaverConfig.InterceptorConfig interceptor = new WeaverConfig.InterceptorConfig();
    interceptor.setClassName("com.example.Order");
    interceptor.setClassPattern("com.example.*");
    interceptor.setMethod("place");
    interceptor.setMethodPattern("place*");
    interceptor.setBefore("audit.Audit#before");
    interceptor.setAfter("audit.Audit#after");
    interceptor.setAround("audit.Audit#around");
    interceptor.setPriority(5);
    interceptor.setClassAnnotation("com.example.Traced");
    interceptor.setSuperClass("com.example.Base");
    interceptor.setInterfaceName("com.example.Api");
    interceptor.setMethodAnnotation("com.example.Timed");
    interceptor.setMethodSignature("place(String)");
    interceptor.setPointcut("execution(* com.example..*(..))");

    assertEquals("com.example.Order", interceptor.getClassName());
    assertEquals("com.example.*", interceptor.getClassPattern());
    assertEquals("place", interceptor.getMethod());
    assertEquals("place*", interceptor.getMethodPattern());
    assertEquals("audit.Audit#before", interceptor.getBefore());
    assertEquals("audit.Audit#after", interceptor.getAfter());
    assertEquals("audit.Audit#around", interceptor.getAround());
    assertEquals(5, interceptor.getPriority());
    assertEquals("com.example.Traced", interceptor.getClassAnnotation());
    assertEquals("com.example.Base", interceptor.getSuperClass());
    assertEquals("com.example.Api", interceptor.getInterfaceName());
    assertEquals("com.example.Timed", interceptor.getMethodAnnotation());
    assertEquals("place(String)", interceptor.getMethodSignature());
    assertEquals("execution(* com.example..*(..))", interceptor.getPointcut());
  }
}
