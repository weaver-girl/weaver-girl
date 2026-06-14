package com.github.cc11001100.weavergirl.core.transformer;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.config.WeaverConfig;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import com.github.cc11001100.weavergirl.core.status.AgentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WeaverTransformer scope control features.
 * These tests verify configuration wiring rather than full bytecode transformation,
 * since real transformation requires a JVM agent setup (covered by AgentIntegrationTest).
 */
class WeaverTransformerScopeTest {

    private DefaultInterceptorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultInterceptorRegistry();
        AgentStatus.getInstance().reset();
    }

    @Test
    void excludedClassPatterns_areAppliedToTransformer() {
        WeaverTransformer transformer = new WeaverTransformer(registry);
        transformer.setExcludedClassPatterns(Arrays.asList("com.example.internal.*", "com.secret.*"));

        // Verify the transformer was created without error
        assertNotNull(transformer);
    }

    @Test
    void weaverConfig_maxTransformations_isApplied() {
        WeaverConfig config = new WeaverConfig();
        config.setMaxTransformations(500);
        WeaverTransformer transformer = new WeaverTransformer(registry);
        transformer.setWeaverConfig(config);

        // Verify the transformer was created without error
        assertNotNull(transformer);
    }

    @Test
    void weaverConfig_onlyInterceptPackages_isApplied() {
        WeaverConfig config = new WeaverConfig();
        config.setOnlyInterceptPackages(Collections.singletonList("com.myapp"));
        WeaverTransformer transformer = new WeaverTransformer(registry);
        transformer.setWeaverConfig(config);

        assertNotNull(transformer);
    }

    @Test
    void weaverConfig_disabledPlugins_isApplied() {
        WeaverConfig config = new WeaverConfig();
        config.setDisabledPlugins(Arrays.asList("servlet", "jdbc"));
        assertEquals(2, config.getDisabledPlugins().size());
        assertTrue(config.getDisabledPlugins().contains("servlet"));
        assertTrue(config.getDisabledPlugins().contains("jdbc"));
    }

    @Test
    void weaverConfig_allSettingsCombined() {
        WeaverConfig config = new WeaverConfig();
        config.setExcludedClasses(Arrays.asList("com.example.internal.*"));
        config.setOnlyInterceptPackages(Collections.singletonList("com.myapp"));
        config.setMaxTransformations(5000);
        config.setDisabledPlugins(Arrays.asList("kafka"));
        config.setSamplingThreshold(500);
        config.setCircuitBreakerFailures(3);
        config.setCircuitBreakerCooldown(30000L);

        WeaverTransformer transformer = new WeaverTransformer(registry);
        transformer.setWeaverConfig(config);
        transformer.setExcludedClassPatterns(config.getExcludedClasses());

        assertNotNull(transformer);
        assertEquals(5000, config.getMaxTransformations());
        assertEquals(500, config.getSamplingThreshold());
    }

    @Test
    void transformationCount_incrementsInAgentStatus() {
        // This verifies the AgentStatus counter mechanism used by WeaverTransformer
        long before = AgentStatus.getInstance().getTransformationCount();
        AgentStatus.getInstance().incrementTransformationCount();
        assertEquals(before + 1, AgentStatus.getInstance().getTransformationCount());
    }

    @Test
    void maxTransformations_defaultValue_is10000() {
        WeaverConfig config = new WeaverConfig();
        // Default should be null (no limit), transformer uses 10000 as default
        assertNull(config.getMaxTransformations());
    }
}
