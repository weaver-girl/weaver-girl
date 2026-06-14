package com.github.cc11001100.weavergirl.api.plugin;

import java.io.*;
import java.util.*;

/**
 * Plugin metadata loaded from {@code META-INF/weaver-girl-plugin.properties}.
 *
 * <p>This file provides structured metadata about a plugin JAR:</p>
 * <pre>
 * # META-INF/weaver-girl-plugin.properties
 * plugin.name=my-custom-plugin
 * plugin.version=1.2.0
 * plugin.description=Custom instrumentation for XYZ framework
 * plugin.author=Team Example
 * plugin.depends=jdbc,redis
 * plugin.targetFrameworks=xyz-core,xyz-web
 * plugin.minimumAgentVersion=1.0.0
 * plugin.sha256=&lt;expected-sha256-of-jar&gt;
 * </pre>
 *
 * @since 1.1.0
 */
public class PluginMetadata {

    /** Metadata file location in JAR. */
    public static final String METADATA_PATH = "META-INF/weaver-girl-plugin.properties";

    private final String name;
    private final String version;
    private final String description;
    private final String author;
    private final List<String> depends;
    private final List<String> targetFrameworks;
    private final String minimumAgentVersion;
    private final String expectedSha256;

    private PluginMetadata(Builder builder) {
        this.name = builder.name;
        this.version = builder.version;
        this.description = builder.description;
        this.author = builder.author;
        this.depends = Collections.unmodifiableList(new ArrayList<>(builder.depends));
        this.targetFrameworks = Collections.unmodifiableList(new ArrayList<>(builder.targetFrameworks));
        this.minimumAgentVersion = builder.minimumAgentVersion;
        this.expectedSha256 = builder.expectedSha256;
    }

    /** Plugin name (required). */
    public String getName() { return name; }

    /** Plugin version in semver format. */
    public String getVersion() { return version; }

    /** Human-readable description. */
    public String getDescription() { return description; }

    /** Plugin author/organization. */
    public String getAuthor() { return author; }

    /** List of plugin names this plugin depends on. */
    public List<String> getDepends() { return depends; }

    /** Target frameworks this plugin instruments. */
    public List<String> getTargetFrameworks() { return targetFrameworks; }

    /** Minimum agent version required. */
    public String getMinimumAgentVersion() { return minimumAgentVersion; }

    /** Expected SHA-256 of the JAR for integrity verification. */
    public String getExpectedSha256() { return expectedSha256; }

    /**
     * Load plugin metadata from a properties input stream.
     *
     * @param is the input stream for the properties file
     * @return parsed metadata
     */
    public static PluginMetadata fromProperties(InputStream is) throws IOException {
        Properties props = new Properties();
        props.load(is);

        Builder builder = builder()
                .name(props.getProperty("plugin.name", "unknown"))
                .version(props.getProperty("plugin.version", "0.0.0"))
                .description(props.getProperty("plugin.description", ""))
                .author(props.getProperty("plugin.author", ""))
                .minimumAgentVersion(props.getProperty("plugin.minimumAgentVersion"))
                .expectedSha256(props.getProperty("plugin.sha256"));

        String deps = props.getProperty("plugin.depends", "");
        if (!deps.isEmpty()) {
            for (String dep : deps.split(",")) {
                String trimmed = dep.trim();
                if (!trimmed.isEmpty()) {
                    builder.depend(trimmed);
                }
            }
        }

        String targets = props.getProperty("plugin.targetFrameworks", "");
        if (!targets.isEmpty()) {
            for (String target : targets.split(",")) {
                String trimmed = target.trim();
                if (!trimmed.isEmpty()) {
                    builder.targetFramework(trimmed);
                }
            }
        }

        return builder.build();
    }

    /**
     * Create a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for PluginMetadata.
     */
    public static class Builder {
        private String name = "unknown";
        private String version = "0.0.0";
        private String description = "";
        private String author = "";
        private final List<String> depends = new ArrayList<>();
        private final List<String> targetFrameworks = new ArrayList<>();
        private String minimumAgentVersion;
        private String expectedSha256;

        public Builder name(String name) { this.name = name; return this; }
        public Builder version(String version) { this.version = version; return this; }
        public Builder description(String desc) { this.description = desc; return this; }
        public Builder author(String author) { this.author = author; return this; }
        public Builder depend(String pluginName) { this.depends.add(pluginName); return this; }
        public Builder targetFramework(String framework) { this.targetFrameworks.add(framework); return this; }
        public Builder minimumAgentVersion(String ver) { this.minimumAgentVersion = ver; return this; }
        public Builder expectedSha256(String sha256) { this.expectedSha256 = sha256; return this; }

        public PluginMetadata build() {
            return new PluginMetadata(this);
        }
    }

    @Override
    public String toString() {
        return "PluginMetadata{name='" + name + "', version='" + version
                + "', depends=" + depends + "}";
    }
}
