// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/config/YamlConfigLoaderTest.java
package com.github.cc11001100.weavergirl.core.config;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.io.StringReader;
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
      Files.writeString(file, "interceptors: []\n");
      WeaverConfig config = loader.parseFromFile(file.toString());
      assertEquals(100, config.getSamplingThreshold());
      assertEquals("INFO", config.getLogLevel());

      Files.writeString(file, "not: [valid");
      assertNotNull(loader.parseFromFile(file.toString()));
    } finally {
      Files.deleteIfExists(file);
    }
  }
}
