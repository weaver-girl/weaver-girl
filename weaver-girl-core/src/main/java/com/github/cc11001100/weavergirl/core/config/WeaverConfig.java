package com.github.cc11001100.weavergirl.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Data model for weaver-girl YAML configuration.
 */
public class WeaverConfig {

    private List<InterceptorConfig> interceptors = new ArrayList<>();

    // Agent-level settings
    private Integer samplingThreshold;      // invocations/second threshold
    private Integer circuitBreakerFailures;  // consecutive failures before opening
    private Long circuitBreakerCooldown;     // cooldown in milliseconds
    private List<String> excludedClasses;    // class name patterns to exclude from instrumentation
    private String logLevel;                 // TRACE, DEBUG, INFO, WARN, ERROR
    private Integer maxTransformations;      // max number of classes to transform
    private List<String> onlyInterceptPackages; // limit instrumentation to these packages

    public List<InterceptorConfig> getInterceptors() {
        return interceptors;
    }

    public void setInterceptors(List<InterceptorConfig> interceptors) {
        this.interceptors = interceptors != null ? interceptors : new ArrayList<>();
    }

    public Integer getSamplingThreshold() {
        return samplingThreshold;
    }

    public void setSamplingThreshold(Integer samplingThreshold) {
        this.samplingThreshold = samplingThreshold;
    }

    public Integer getCircuitBreakerFailures() {
        return circuitBreakerFailures;
    }

    public void setCircuitBreakerFailures(Integer circuitBreakerFailures) {
        this.circuitBreakerFailures = circuitBreakerFailures;
    }

    public Long getCircuitBreakerCooldown() {
        return circuitBreakerCooldown;
    }

    public void setCircuitBreakerCooldown(Long circuitBreakerCooldown) {
        this.circuitBreakerCooldown = circuitBreakerCooldown;
    }

    public List<String> getExcludedClasses() {
        return excludedClasses;
    }

    public void setExcludedClasses(List<String> excludedClasses) {
        this.excludedClasses = excludedClasses;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }

    public Integer getMaxTransformations() {
        return maxTransformations;
    }

    public void setMaxTransformations(Integer maxTransformations) {
        this.maxTransformations = maxTransformations;
    }

    public List<String> getOnlyInterceptPackages() {
        return onlyInterceptPackages;
    }

    public void setOnlyInterceptPackages(List<String> onlyInterceptPackages) {
        this.onlyInterceptPackages = onlyInterceptPackages;
    }

    public static class InterceptorConfig {
        private String className;
        private String classPattern;
        private String method;
        private String methodPattern;
        private String before;
        private String after;
        private String around;
        private int priority;

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public String getClassPattern() { return classPattern; }
        public void setClassPattern(String classPattern) { this.classPattern = classPattern; }

        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }

        public String getMethodPattern() { return methodPattern; }
        public void setMethodPattern(String methodPattern) { this.methodPattern = methodPattern; }

        public String getBefore() { return before; }
        public void setBefore(String before) { this.before = before; }

        public String getAfter() { return after; }
        public void setAfter(String after) { this.after = after; }

        public String getAround() { return around; }
        public void setAround(String around) { this.around = around; }

        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
    }
}
