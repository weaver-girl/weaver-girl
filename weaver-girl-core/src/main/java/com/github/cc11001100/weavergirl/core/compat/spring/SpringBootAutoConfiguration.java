package com.github.cc11001100.weavergirl.core.compat.spring;

import com.github.cc11001100.weavergirl.core.compat.RuntimeCompatibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Boot integration helper.
 *
 * <p>Provides auto-configuration support when Spring Boot is detected.
 * Configures agent behavior optimized for Spring Boot applications:</p>
 * <ul>
 *   <li>Sets up appropriate class exclusion patterns for Spring internals</li>
 *   <li>Configures plugin behavior based on detected Spring components</li>
 *   <li>Adapts sampling rates for typical Spring Boot workloads</li>
 * </ul>
 *
 * <p>Note: This class does NOT depend on Spring classes directly — it uses
 * reflection-based detection to remain compatible without compile-time
 * dependencies.</p>
 *
 * @since 1.1.0
 */
public class SpringBootAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SpringBootAutoConfiguration.class);

    /** Recommended excluded class patterns for Spring Boot applications. */
    private static final String[] SPRING_BOOT_EXCLUSIONS = {
            "org.springframework.*",
            "org.apache.tomcat.*",
            "org.apache.catalina.*",
            "org.hibernate.*",
            "com.zaxxer.hikari.*",
            "io.undertow.*",
            "org.eclipse.jetty.*",
            "ch.qos.logback.*",
            "org.slf4j.*",
    };

    private SpringBootAutoConfiguration() {
    }

    /**
     * Check if Spring Boot auto-configuration should be applied.
     *
     * @return true if Spring Boot is detected
     */
    public static boolean shouldApply() {
        return RuntimeCompatibility.hasSpringBoot();
    }

    /**
     * Get recommended configuration for a Spring Boot application.
     *
     * @return map of recommended config key-value pairs
     */
    public static Map<String, String> getRecommendedConfig() {
        Map<String, String> config = new LinkedHashMap<>();

        if (RuntimeCompatibility.hasSpringBoot()) {
            config.put("excludedClasses", String.join(",", SPRING_BOOT_EXCLUSIONS));
            log.info("[SpringBoot] Applied recommended exclusions for Spring Boot");
        }

        // If WebFlux detected, suggest adapted sampling
        if (RuntimeCompatibility.hasReactiveStack()) {
            config.put("samplingRate", "10"); // Higher rate for reactive workloads
            config.put("samplingThreshold", "20000");
            log.info("[SpringBoot] Reactive stack detected — adapted sampling config");
        }

        return config;
    }

    /**
     * Get recommended class exclusion patterns.
     */
    public static String[] getExclusionPatterns() {
        return SPRING_BOOT_EXCLUSIONS.clone();
    }
}
