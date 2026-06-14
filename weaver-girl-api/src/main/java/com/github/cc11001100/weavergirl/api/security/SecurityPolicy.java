package com.github.cc11001100.weavergirl.api.security;

import java.util.*;

/**
 * Security policy controlling which classes and methods can be intercepted.
 *
 * <p>Supports allow-list and deny-list patterns. Deny takes precedence over allow.</p>
 *
 * <h3>Configuration example:</h3>
 * <pre>
 * SecurityPolicy policy = SecurityPolicy.builder()
 *     .allowPattern("com.example.service.*")
 *     .allowPattern("com.example.controller.*")
 *     .denyPattern("com.example.service.AuthService")        // block sensitive class
 *     .denyPattern("*.*password*")                              // block password methods
 *     .denyPattern("*.*secret*")
 *     .build();
 * </pre>
 *
 * @since 1.1.0
 */
public class SecurityPolicy {

    private final List<String> allowPatterns;
    private final List<String> denyPatterns;
    private final boolean defaultAllow;
    private final boolean auditAllInterceptions;

    private SecurityPolicy(Builder builder) {
        this.allowPatterns = Collections.unmodifiableList(new ArrayList<>(builder.allowPatterns));
        this.denyPatterns = Collections.unmodifiableList(new ArrayList<>(builder.denyPatterns));
        this.defaultAllow = builder.defaultAllow;
        this.auditAllInterceptions = builder.auditAllInterceptions;
    }

    /**
     * Check if interception of the given class is allowed.
     *
     * @param className fully qualified class name
     * @return true if interception is permitted
     */
    public boolean isClassAllowed(String className) {
        // Deny takes precedence
        for (String pattern : denyPatterns) {
            if (matchesPattern(className, pattern)) {
                return false;
            }
        }
        // If allow list is empty, use default
        if (allowPatterns.isEmpty()) {
            return defaultAllow;
        }
        // Allow list specified — must match at least one
        for (String pattern : allowPatterns) {
            if (matchesPattern(className, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if interception of the given method is allowed.
     *
     * @param className  fully qualified class name
     * @param methodName method name
     * @return true if interception is permitted
     */
    public boolean isMethodAllowed(String className, String methodName) {
        String fullName = className + "." + methodName;
        // Deny takes precedence — check class, method, and full name
        for (String pattern : denyPatterns) {
            if (matchesPattern(className, pattern)
                    || matchesPattern(methodName, pattern)
                    || matchesPattern(fullName, pattern)) {
                return false;
            }
        }
        if (allowPatterns.isEmpty()) {
            return defaultAllow;
        }
        for (String pattern : allowPatterns) {
            if (matchesPattern(className, pattern)
                    || matchesPattern(fullName, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether all interceptions should be audit-logged.
     */
    public boolean shouldAuditAll() {
        return auditAllInterceptions;
    }

    /** Get allow patterns. */
    public List<String> getAllowPatterns() { return allowPatterns; }

    /** Get deny patterns. */
    public List<String> getDenyPatterns() { return denyPatterns; }

    private boolean matchesPattern(String text, String pattern) {
        if (pattern.equals("*")) return true;
        if (pattern.endsWith(".*")) {
            return text.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        if (pattern.startsWith("*.")) {
            return text.endsWith(pattern.substring(1));
        }
        if (pattern.contains("*")) {
            // Simple glob: convert to regex
            String regex = pattern.replace(".", "\\.").replace("*", ".*");
            return text.matches(regex);
        }
        return text.equals(pattern);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<String> allowPatterns = new ArrayList<>();
        private final List<String> denyPatterns = new ArrayList<>();
        private boolean defaultAllow = true;
        private boolean auditAllInterceptions = false;

        public Builder allowPattern(String pattern) { allowPatterns.add(pattern); return this; }
        public Builder denyPattern(String pattern) { denyPatterns.add(pattern); return this; }
        public Builder defaultAllow(boolean allow) { this.defaultAllow = allow; return this; }
        public Builder auditAllInterceptions(boolean audit) { this.auditAllInterceptions = audit; return this; }

        public SecurityPolicy build() {
            return new SecurityPolicy(this);
        }
    }
}
