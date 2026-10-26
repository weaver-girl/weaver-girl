// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/config/YamlConfigLoaderTest.java
package com.github.cc11001100.weavergirl.core.config;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.io.StringReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class YamlConfigLoaderTest {

  private static final String VALID_ADVICE =
      "com.github.cc11001100.weavergirl.core.config.TestInterceptor";

  private InterceptorRegistry registry;
  private YamlConfigLoader loader;

  @BeforeEach
  void setUp() {
    registry = new DefaultInterceptorRegistry();
    loader = new YamlConfigLoader();
  }

  @Test
  void loadFromReader_validYaml_registersInterceptors() {
    String yaml =
        "interceptors:\n"
            + "  - className: \"com.example.UserService\"\n"
            + "    method: \"createUser\"\n"
            + "    before: \""
            + VALID_ADVICE
            + "\"\n";

    WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
    assertNotNull(config);
    assertEquals(1, config.getInterceptors().size());
    assertEquals("com.example.UserService", config.getInterceptors().get(0).getClassName());

    List<InterceptorDefinition> defs = registry.getInterceptorsForClass("com.example.UserService");
    assertEquals(1, defs.size());
  }

  @Test
  void loadFromReader_multipleInterceptors_registersAll() {
    String yaml =
        "interceptors:\n"
            + "  - className: \"com.example.ServiceA\"\n"
            + "    method: \"doWork\"\n"
            + "    before: \""
            + VALID_ADVICE
            + "\"\n"
            + "  - className: \"com.example.ServiceB\"\n"
            + "    methodPattern: \"process.*\"\n"
            + "    after: \""
            + VALID_ADVICE
            + "\"\n";

    loader.loadFromReader(new StringReader(yaml), registry);
    assertEquals(2, registry.getAllDefinitions().size());
  }

  @Test
  void loadFromReader_invalidYaml_returnsEmptyConfig() {
    String yaml = "not: valid: yaml: {{{";
    WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
    assertNotNull(config);
  }

  @Test
  void loadFromReader_emptyYaml_returnsEmptyConfig() {
    String yaml = "";
    WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
    assertNotNull(config);
    assertTrue(config.getInterceptors().isEmpty());
  }

  @Test
  void loadFromFile_nonExistentFile_returnsEmptyConfig() {
    WeaverConfig config = loader.loadFromFile("/nonexistent/path.yml", registry);
    assertNotNull(config);
    assertTrue(config.getInterceptors().isEmpty());
  }

  // --- Validation tests ---

  @Test
  void validate_noClassNameOrClassPattern_skipsEntry() {
    String yaml =
        "interceptors:\n" + "  - method: \"doWork\"\n" + "    before: \"" + VALID_ADVICE + "\"\n";

    WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
    assertTrue(
        config.getInterceptors().isEmpty(),
        "Interceptor without className or classPattern should be skipped");
    assertEquals(0, registry.getAllDefinitions().size());
  }

  @Test
  void validate_noAdviceClasses_skipsEntry() {
    String yaml =
        "interceptors:\n"
            + "  - className: \"com.example.ServiceA\"\n"
            + "    method: \"doWork\"\n";

    WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
    assertTrue(
        config.getInterceptors().isEmpty(),
        "Interceptor with no before/after/around advice should be skipped");
    assertEquals(0, registry.getAllDefinitions().size());
  }

  @Test
  void validate_adviceClassNotFound_stillRegistered_lazilyValidated() {
    // Validation is structural only — advice classes are validated lazily at invocation time.
    // This is by design: the class may be loaded by a different ClassLoader.
    String yaml =
        "interceptors:\n"
            + "  - className: \"com.example.ServiceA\"\n"
            + "    before: \"com.nonexistent.AdviceClass\"\n";

    WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
    assertEquals(
        1,
        config.getInterceptors().size(),
        "Interceptor with non-existent advice class should still be registered (lazy validation)");
    assertEquals(1, registry.getAllDefinitions().size());
  }

  @Test
  void validate_adviceClassNotImplementingInterceptor_stillRegistered_lazilyValidated() {
    // Validation is structural only — the class may implement Interceptor in a different
    // ClassLoader
    String yaml =
        "interceptors:\n"
            + "  - className: \"com.example.ServiceA\"\n"
            + "    before: \"com.github.cc11001100.weavergirl.core.config.NotAnInterceptor\"\n";

    WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
    assertEquals(
        1,
        config.getInterceptors().size(),
        "Interceptor with non-Interceptor advice class should still be registered (lazy"
            + " validation)");
    assertEquals(1, registry.getAllDefinitions().size());
  }

  @Test
  void validate_validInterceptor_passesValidation() {
    String yaml =
        "interceptors:\n"
            + "  - className: \"com.example.ServiceA\"\n"
            + "    before: \""
            + VALID_ADVICE
            + "\"\n";

    WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
    assertEquals(1, config.getInterceptors().size(), "Valid interceptor should pass validation");
    assertEquals(1, registry.getAllDefinitions().size());
  }

  @Test
  void validate_mixedValidAndInvalid_onlyStructurallyValidRegistered() {
    String yaml =
        "interceptors:\n"
            + "  - className: \"com.example.ServiceA\"\n"
            + "    before: \""
            + VALID_ADVICE
            + "\"\n"
            + "  - method: \"doWork\"\n"
            + "    before: \""
            + VALID_ADVICE
            + "\"\n"
            + "  - className: \"com.example.ServiceB\"\n"
            + "    after: \"com.nonexistent.Missing\"\n";

    WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
    // Entry 1: valid (has className + before)
    // Entry 2: invalid (no className or classPattern)
    // Entry 3: valid (has className + after) — advice class not checked structurally
    assertEquals(
        2,
        config.getInterceptors().size(),
        "Only structurally valid interceptors should remain (no className is skipped)");
    assertEquals(2, registry.getAllDefinitions().size());
  }

  @Test
  void loadFromReader_supportsMatcherVariantsAndAdvicePhases() {
    String yaml =
        "interceptors:\n"
            + "  - classPattern: 'com\\\\.example\\\\..*'\n"
            + "    methodPattern: 'run.*'\n"
            + "    before: '"
            + VALID_ADVICE
            + "'\n"
            + "  - classAnnotation: 'java.lang.Deprecated'\n"
            + "    methodAnnotation: 'java.lang.Deprecated'\n"
            + "    after: '"
            + VALID_ADVICE
            + "'\n"
            + "  - superClass: 'java.lang.Number'\n"
            + "    methodSignature: 'intValue()'\n"
            + "    around: '"
            + VALID_ADVICE
            + "'\n"
            + "  - interfaceName: 'java.lang.Runnable'\n"
            + "    methodSignature: 'run'\n"
            + "    before: '"
            + VALID_ADVICE
            + "'\n"
            + "  - pointcut: 'execution(* com.example.Service.run(..))'\n"
            + "    around: '"
            + VALID_ADVICE
            + "'\n";

    loader.loadFromReader(new StringReader(yaml), registry);
    assertEquals(5, registry.getAllDefinitions().size());
    MethodInvocation invocation = new MethodInvocation(getClass(), "run", null, null);
    for (InterceptorDefinition definition : registry.getAllDefinitions()) {
      definition.getInterceptor().before(invocation);
      definition.getInterceptor().after(invocation);
      definition.getInterceptor().onException(invocation);
    }
  }

  @Test
  void parseFromFile_appliesDefaultsAndHandlesMalformedFile() throws Exception {
    Path file = Files.createTempFile("weaver-girl", ".yml");
    try {
      Files.write(file, "interceptors: []\n".getBytes(StandardCharsets.UTF_8));
      WeaverConfig config = loader.parseFromFile(file.toString());
      assertEquals(100, config.getSamplingThreshold());
      assertEquals("INFO", config.getLogLevel());

      Files.write(file, "not: [valid".getBytes(StandardCharsets.UTF_8));
      assertNotNull(loader.parseFromFile(file.toString()));
    } finally {
      Files.deleteIfExists(file);
    }
  }

  /**
   * A plugin section used to make the typed binding reject the whole document, so the interceptor
   * definitions in the same file were lost along with it.
   */
  @Test
  void loadFromString_pluginSectionCoexistsWithInterceptorDefinitions() {
    String yaml =
        "plugins:\n"
            + "  jdbc:\n"
            + "    slowQueryThreshold: 1000\n"
            + "interceptors:\n"
            + "  - className: com.example.Svc\n"
            + "    method: run\n"
            + "    before: '"
            + VALID_ADVICE
            + "'\n";

    WeaverConfig config = loader.loadFromString(yaml, registry);

    assertEquals(1, config.getInterceptors().size());
    assertEquals(1, registry.getAllDefinitions().size());
  }

  @Test
  void parseFromFile_flattensPluginSectionsIntoNamespacedKeys() throws Exception {
    Path file = Files.createTempFile("weaver-girl-plugins", ".yml");
    try {
      Files.write(
          file,
          ("plugins:\n"
                  + "  jdbc:\n"
                  + "    slowQueryThreshold: 1000\n"
                  + "    logSql: true\n"
                  + "  method-timing:\n"
                  + "    slowThreshold: 100\n")
              .getBytes(StandardCharsets.UTF_8));

      WeaverConfig config = loader.parseFromFile(file.toString());

      assertEquals("1000", config.getPluginConfig().get("plugins.jdbc.slowQueryThreshold"));
      assertEquals("true", config.getPluginConfig().get("plugins.jdbc.logSql"));
      assertEquals("100", config.getPluginConfig().get("plugins.method-timing.slowThreshold"));
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void parseFromFile_keepsTopLevelCapabilityKeysAsLaunchOptions() throws Exception {
    Path file = Files.createTempFile("weaver-girl-capabilities", ".yml");
    try {
      Files.write(
          file,
          ("metricsPort: 9400\n"
                  + "apiPort: 9402\n"
                  + "watch: true\n"
                  + "samplingThreshold: 500\n")
              .getBytes(StandardCharsets.UTF_8));

      WeaverConfig config = loader.parseFromFile(file.toString());

      assertEquals("9400", config.getPluginConfig().get("metricsPort"));
      assertEquals("9402", config.getPluginConfig().get("apiPort"));
      assertEquals("true", config.getPluginConfig().get("watch"));
      // A real agent setting stays an agent setting and is not handed to plugins as well.
      assertFalse(config.getPluginConfig().containsKey("samplingThreshold"));
      assertEquals(500, config.getSamplingThreshold());
    } finally {
      Files.deleteIfExists(file);
    }
  }

  /**
   * A nested value that is not a scalar is not a plugin option, and a null key has nothing to name, so
   * both are skipped instead of being handed to a plugin.
   */
  @Test
  void nestedNonScalarsAndANullKeyAreSkipped() throws Exception {
    Path file = Files.createTempFile("weaver-girl-nested", ".yml");
    try {
      Files.write(
          file,
          ("metricsPort: 9400\n"
                  + "plugins:\n"
                  + "  jdbc:\n"
                  + "    slowQueryThreshold: 1000\n"
                  + "    tags:\n"
                  + "      - slow\n"
                  + "    limits: [1, 2]\n"
                  + "    ?\n"
                  + "    note: ~\n")
              .getBytes(StandardCharsets.UTF_8));

      WeaverConfig config = loader.parseFromFile(file.toString());

      assertEquals("9400", config.getPluginConfig().get("metricsPort"));
      assertEquals("1000", config.getPluginConfig().get("plugins.jdbc.slowQueryThreshold"));
      // A list is not a settings map, so it is kept as its printed form rather than expanded.
      assertEquals("[slow]", config.getPluginConfig().get("plugins.jdbc.tags"));
      assertEquals("[1, 2]", config.getPluginConfig().get("plugins.jdbc.limits"));
      // A null value names nothing, and a null key has no name, so neither is kept.
      assertFalse(config.getPluginConfig().containsKey("plugins.jdbc.note"));
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void parseFromFile_acceptsPluginDirectoryAsScalar() throws Exception {
    Path file = Files.createTempFile("weaver-girl-plugin-dir", ".yml");
    try {
      Files.write(file, "plugins: /opt/agent/plugins\n".getBytes(StandardCharsets.UTF_8));

      WeaverConfig config = loader.parseFromFile(file.toString());

      assertEquals("/opt/agent/plugins", config.getPluginConfig().get("plugins"));
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void loadFromFile_readsARealFileAndRejectsBrokenRegexes() throws Exception {
    Path file = Files.createTempFile("weaver-girl", ".yml");
    try {
      Files.write(
          file,
          ("interceptors:\n  - className: com.example.Svc\n    before: '" + VALID_ADVICE + "'\n")
              .getBytes(StandardCharsets.UTF_8));
      assertEquals(1, loader.loadFromFile(file.toString(), registry).getInterceptors().size());

      // A file with no read permission fails while it is being opened, which is the path a
      // missing or malformed file does not take.
      Path unreadable = Files.createTempFile("weaver-girl-unreadable", ".yml");
      Files.write(unreadable, "interceptors: []\n".getBytes(StandardCharsets.UTF_8));
      assertTrue(unreadable.toFile().setReadable(false));
      try {
        assertNotNull(loader.parseFromFile(unreadable.toString()));
        assertNotNull(loader.loadFromFile(unreadable.toString(), registry));
      } finally {
        unreadable.toFile().setReadable(true);
        Files.deleteIfExists(unreadable);
      }
    } finally {
      Files.deleteIfExists(file);
    }

    String yaml =
        "interceptors:\n"
            + "  - classPattern: '['\n"
            + "    methodPattern: '['\n"
            + "    before: '"
            + VALID_ADVICE
            + "'\n"
            + "  - className: com.example.Other\n"
            + "    method: run\n"
            + "    before: 'java.lang.String'\n"
            + "  - className: com.example.Broken\n"
            + "    method: run\n"
            + "    before: 'com.example.DoesNotExist'\n";
    InterceptorRegistry fresh = new DefaultInterceptorRegistry();
    loader.loadFromReader(new StringReader(yaml), fresh);

    // The broken regex is dropped. The other two register, and invoking them exercises an advice
    // class that is not an interceptor and one that cannot be loaded.
    assertEquals(2, fresh.getAllDefinitions().size());
    MethodInvocation invocation = new MethodInvocation(getClass(), "run", null, null);
    for (InterceptorDefinition definition : fresh.getAllDefinitions()) {
      definition.getInterceptor().before(invocation);
      definition.getInterceptor().before(invocation);
    }
  }

  /** An empty file and a file that is not there both yield an empty config rather than a failure. */
  @Test
  void anEmptyOrMissingFileYieldsAnEmptyConfig() throws Exception {
    Path file = Files.createTempFile("weaver-girl", ".yml");
    try {
      assertTrue(loader.parseFromFile(file.toString()).getInterceptors().isEmpty());
    } finally {
      Files.deleteIfExists(file);
    }

    assertTrue(
        loader.parseFromFile(file.resolveSibling("never-written.yml").toString())
            .getInterceptors()
            .isEmpty());
    assertTrue(
        loader.loadFromFile(file.resolveSibling("never-written.yml").toString(), registry)
            .getInterceptors()
            .isEmpty());
  }

  /**
   * A mapping entry with no key is skipped rather than stored. SnakeYAML parses an explicit {@code ?}
   * as a null key, and the scalar beside it is still collected.
   */
  @Test
  void aNullKeyIsSkippedWhenCollectingPluginOptions() throws Exception {
    Path file = Files.createTempFile("weaver-girl", ".yml");
    try {
      Files.write(file, "metricsPort: 9400\n? \n".getBytes(StandardCharsets.UTF_8));
      assertEquals("9400", loader.parseFromFile(file.toString()).getPluginOptions().get("metricsPort"));
    } finally {
      Files.deleteIfExists(file);
    }
  }

  /** A config with no interceptor section registers nothing. */
  @Test
  void aConfigWithoutInterceptorsRegistersNothing() {
    assertTrue(loader.loadFromString("samplingThreshold: 50\n", registry).getInterceptors().isEmpty());
    assertEquals(0, registry.getAllDefinitions().size());
  }

  /** An interceptor aimed at an interface is named after that interface and still registers. */
  @Test
  void anInterceptorAimedAtAnInterfaceIsNamedAfterIt() {
    String yaml =
        "interceptors:\n"
            + "  - interfaceName: com.example.Api\n"
            + "    before: '"
            + VALID_ADVICE
            + "'\n";

    loader.loadFromString(yaml, registry);

    assertEquals(1, registry.getAllDefinitions().size());
    assertTrue(registry.getAllDefinitions().get(0).getName().contains("implements:com.example.Api"));
  }

  /** Advice whose own method throws is logged and swallowed, so the invocation still finishes. */
  @Test
  void adviceThatThrowsIsSwallowed() {
    String yaml =
        "interceptors:\n"
            + "  - className: com.example.Svc\n"
            + "    before: '"
            + ThrowingAdvice.class.getName()
            + "'\n";
    loader.loadFromString(yaml, registry);
    MethodInvocation invocation = new MethodInvocation(getClass(), "run", null, null);

    assertDoesNotThrow(() -> registry.getAllDefinitions().get(0).getInterceptor().before(invocation));
  }

  /** An advice used to exercise the path where the advice method itself fails. */
  public static final class ThrowingAdvice implements Interceptor {
    @Override
    public void before(MethodInvocation invocation) {
      throw new IllegalStateException("advice failed");
    }
  }
}
