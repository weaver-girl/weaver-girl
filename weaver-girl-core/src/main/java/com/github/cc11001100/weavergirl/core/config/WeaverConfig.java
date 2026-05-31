// weaver-girl-core/src/main/java/com/github/cc11001100/weavergirl/core/config/WeaverConfig.java
package com.github.cc11001100.weavergirl.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Data model for weaver-girl YAML configuration.
 */
public class WeaverConfig {

    private List<InterceptorConfig> interceptors = new ArrayList<>();

    public List<InterceptorConfig> getInterceptors() {
        return interceptors;
    }

    public void setInterceptors(List<InterceptorConfig> interceptors) {
        this.interceptors = interceptors != null ? interceptors : new ArrayList<>();
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
