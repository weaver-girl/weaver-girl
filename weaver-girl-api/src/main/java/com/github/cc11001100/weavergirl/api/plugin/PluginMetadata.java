package com.github.cc11001100.weavergirl.api.plugin;

import java.io.*;
import java.util.*;

/**
 * Plugin metadata loaded from {@code META-INF/weaver-girl-plugin.properties}.
 *
 * <p>This file provides structured metadata about a plugin JAR:
 *
 * <pre>
 * # META-INF/weaver-girl-plugin.properties
 * plugin.name=my-custom-plugin
 * plugin.version=1.2.0
 * plugin.description=Custom instrumentation for XYZ framework
 * plugin.author=Team Example
 * plugin.depends=jdbc,redis
 * plugin.exports=com.example.clock, com.example.clock.Clock class
 * plugin.targetFrameworks=xyz-core,xyz-web
 * plugin.minimumAgentVersion=1.0.0
 * plugin.apiVersion=1.0.0
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
  private final List<String> requires;
  private final List<String> exports;

  /** Which entries of {@link #exports} name one class rather than a package. */
  private final List<Boolean> exportedClasses;
  private final List<String> targetFrameworks;
  private final String minimumAgentVersion;
  private final String apiVersion;
  private final String expectedSha256;

  private PluginMetadata(Builder builder) {
    this.name = builder.name;
    this.version = builder.version;
    this.description = builder.description;
    this.author = builder.author;
    this.depends = Collections.unmodifiableList(new ArrayList<>(builder.depends));
    this.requires = Collections.unmodifiableList(new ArrayList<>(builder.requires));
    this.exports = Collections.unmodifiableList(new ArrayList<>(builder.exports));
    this.exportedClasses = Collections.unmodifiableList(new ArrayList<>(builder.exportedClasses));
    this.targetFrameworks = Collections.unmodifiableList(new ArrayList<>(builder.targetFrameworks));
    this.minimumAgentVersion = builder.minimumAgentVersion;
    this.apiVersion = builder.apiVersion;
    this.expectedSha256 = builder.expectedSha256;
  }

  /** Plugin name (required). */
  public String getName() {
    return name;
  }

  /** Plugin version in semver format. */
  public String getVersion() {
    return version;
  }

  /** Human-readable description. */
  public String getDescription() {
    return description;
  }

  /** Plugin author/organization. */
  public String getAuthor() {
    return author;
  }

  /** List of plugin names this plugin depends on. */
  public List<String> getDepends() {
    return depends;
  }

  /**
   * Plugins this one refuses to load without. A missing entry here keeps the plugin out, whereas {@link
   * #getDepends()} only orders initialization and tolerates a dependency that was never loaded.
   *
   * @since 1.0.0
   */
  public List<String> getRequires() {
    return requires;
  }

  /**
   * Packages this plugin exposes to the plugins that {@code depend} on it, so one plugin can publish a
   * service interface another consumes without that interface having to live in the shared API.
   *
   * <p>Each entry is a package name, or a single class name when {@link #isExportedClass(int)} says so. A
   * package exposes every class directly in it; a class exposes that one class and nothing else in its package.
   * Everything the plugin does not name stays inside its own class loader.
   *
   * @since 1.0.0
   */
  public List<String> getExports() {
    return exports;
  }

  /**
   * Whether {@link #getExports() export} {@code index} names one class rather than a package. A package and a
   * class are written the same way, so the metadata says which was meant.
   */
  public boolean isExportedClass(int index) {
    return index >= 0 && index < exportedClasses.size() && exportedClasses.get(index);
  }

  /** Target frameworks this plugin instruments. */
  public List<String> getTargetFrameworks() {
    return targetFrameworks;
  }

  /** Minimum agent version required. */
  public String getMinimumAgentVersion() {
    return minimumAgentVersion;
  }

  /**
   * Plugin contract version this plugin was compiled against, or {@code null} when the metadata does
   * not say. Compared with {@link WeaverPlugin#API_VERSION}: a plugin that needs a newer contract
   * than the running agent provides is not loaded.
   *
   * @since 1.0.0
   */
  public String getApiVersion() {
    return apiVersion;
  }

  /** Expected SHA-256 of the JAR for integrity verification. */
  public String getExpectedSha256() {
    return expectedSha256;
  }

  /**
   * Load plugin metadata from a properties input stream.
   *
   * @param is the input stream for the properties file
   * @return parsed metadata
   */
  public static PluginMetadata fromProperties(InputStream is) throws IOException {
    Properties props = new Properties();
    props.load(is);

    Builder builder =
        builder()
            .name(props.getProperty("plugin.name", "unknown"))
            .version(props.getProperty("plugin.version", "0.0.0"))
            .description(props.getProperty("plugin.description", ""))
            .author(props.getProperty("plugin.author", ""))
            .minimumAgentVersion(props.getProperty("plugin.minimumAgentVersion"))
            .apiVersion(props.getProperty("plugin.apiVersion"))
            .expectedSha256(props.getProperty("plugin.sha256"));

    addAll(builder, props.getProperty("plugin.depends", ""), "depend");
    addAll(builder, props.getProperty("plugin.requires", ""), "require");
    addAll(builder, props.getProperty("plugin.exports", ""), "export");

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
   * Split a comma separated property into {@code depend}, {@code require} or {@code export} entries. An export
   * ending in {@code class} names one class; a package and a class are spelled the same way, and guessing from
   * the capitalisation would get a package named after its product wrong.
   */
  private static void addAll(Builder builder, String value, String into) {
    if (value.isEmpty()) {
      return;
    }
    for (String part : value.split(",")) {
      String trimmed = part.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if ("depend".equals(into)) {
        builder.depend(trimmed);
      } else if ("require".equals(into)) {
        builder.require(trimmed);
      } else {
        boolean className = trimmed.endsWith(" class");
        String name =
            className ? trimmed.substring(0, trimmed.length() - " class".length()).trim() : trimmed;
        builder.export(name, className);
      }
    }
  }

  /**
   * The same metadata under a different plugin name.
   *
   * <p>A jar's metadata describes the jar, while the duplicate-name check has to run against each
   * plugin the jar actually contains. Those plugins do not necessarily share the jar's name.
   */
  public PluginMetadata withName(String pluginName) {
    Builder copy =
        builder()
            .name(pluginName)
            .version(version)
            .description(description)
            .author(author)
            .minimumAgentVersion(minimumAgentVersion)
            .apiVersion(apiVersion)
            .expectedSha256(expectedSha256);
    for (String dependency : depends) {
      copy.depend(dependency);
    }
    for (String requirement : requires) {
      copy.require(requirement);
    }
    for (int i = 0; i < exports.size(); i++) {
      copy.export(exports.get(i), isExportedClass(i));
    }
    for (String framework : targetFrameworks) {
      copy.targetFramework(framework);
    }
    return copy.build();
  }

  /** Create a new builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for PluginMetadata. */
  public static class Builder {
    private String name = "unknown";
    private String version = "0.0.0";
    private String description = "";
    private String author = "";
    private final List<String> depends = new ArrayList<>();
    private final List<String> requires = new ArrayList<>();
    private final List<String> exports = new ArrayList<>();
    private final List<Boolean> exportedClasses = new ArrayList<>();
    private final List<String> targetFrameworks = new ArrayList<>();
    private String minimumAgentVersion;
    private String apiVersion;
    private String expectedSha256;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder version(String version) {
      this.version = version;
      return this;
    }

    public Builder description(String desc) {
      this.description = desc;
      return this;
    }

    public Builder author(String author) {
      this.author = author;
      return this;
    }

    public Builder depend(String pluginName) {
      this.depends.add(pluginName);
      return this;
    }

    /** Name a plugin this one refuses to load without. */
    public Builder require(String pluginName) {
      this.requires.add(pluginName);
      return this;
    }

    /** Expose {@code packageName} to the plugins that depend on this one. */
    public Builder export(String packageName) {
      return export(packageName, false);
    }

    /**
     * Expose {@code name} to the plugins that depend on this one. {@code className} names one class; without it
     * {@code name} is a package and every class directly in it is exposed.
     */
    public Builder export(String name, boolean className) {
      this.exports.add(name);
      this.exportedClasses.add(className);
      return this;
    }

    public Builder targetFramework(String framework) {
      this.targetFrameworks.add(framework);
      return this;
    }

    public Builder minimumAgentVersion(String ver) {
      this.minimumAgentVersion = ver;
      return this;
    }

    public Builder apiVersion(String apiVersion) {
      this.apiVersion = apiVersion;
      return this;
    }

    public Builder expectedSha256(String sha256) {
      this.expectedSha256 = sha256;
      return this;
    }

    public PluginMetadata build() {
      return new PluginMetadata(this);
    }
  }

  @Override
  public String toString() {
    return "PluginMetadata{name='"
        + name
        + "', version='"
        + version
        + "', depends="
        + depends
        + "}";
  }
}
