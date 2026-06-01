// weaver-girl-core/src/test/java/com/github/cc11001100/weavergirl/core/config/YamlConfigLoaderTest.java
package com.github.cc11001100.weavergirl.core.config;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        String yaml = "interceptors:\n" +
                "  - className: \"com.example.UserService\"\n" +
                "    method: \"createUser\"\n" +
                "    before: \"" + VALID_ADVICE + "\"\n";

        WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
        assertNotNull(config);
        assertEquals(1, config.getInterceptors().size());
        assertEquals("com.example.UserService", config.getInterceptors().get(0).getClassName());

        List<InterceptorDefinition> defs = registry.getInterceptorsForClass("com.example.UserService");
        assertEquals(1, defs.size());
    }

    @Test
    void loadFromReader_multipleInterceptors_registersAll() {
        String yaml = "interceptors:\n" +
                "  - className: \"com.example.ServiceA\"\n" +
                "    method: \"doWork\"\n" +
                "    before: \"" + VALID_ADVICE + "\"\n" +
                "  - className: \"com.example.ServiceB\"\n" +
                "    methodPattern: \"process.*\"\n" +
                "    after: \"" + VALID_ADVICE + "\"\n";

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
        String yaml = "interceptors:\n" +
                "  - method: \"doWork\"\n" +
                "    before: \"" + VALID_ADVICE + "\"\n";

        WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
        assertTrue(config.getInterceptors().isEmpty(),
                "Interceptor without className or classPattern should be skipped");
        assertEquals(0, registry.getAllDefinitions().size());
    }

    @Test
    void validate_noAdviceClasses_skipsEntry() {
        String yaml = "interceptors:\n" +
                "  - className: \"com.example.ServiceA\"\n" +
                "    method: \"doWork\"\n";

        WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
        assertTrue(config.getInterceptors().isEmpty(),
                "Interceptor with no before/after/around advice should be skipped");
        assertEquals(0, registry.getAllDefinitions().size());
    }

    @Test
    void validate_adviceClassNotFound_skipsEntry() {
        String yaml = "interceptors:\n" +
                "  - className: \"com.example.ServiceA\"\n" +
                "    before: \"com.nonexistent.AdviceClass\"\n";

        WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
        assertTrue(config.getInterceptors().isEmpty(),
                "Interceptor with non-existent advice class should be skipped");
        assertEquals(0, registry.getAllDefinitions().size());
    }

    @Test
    void validate_adviceClassNotImplementingInterceptor_skipsEntry() {
        String yaml = "interceptors:\n" +
                "  - className: \"com.example.ServiceA\"\n" +
                "    before: \"com.github.cc11001100.weavergirl.core.config.NotAnInterceptor\"\n";

        WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
        assertTrue(config.getInterceptors().isEmpty(),
                "Interceptor with advice class not implementing Interceptor should be skipped");
        assertEquals(0, registry.getAllDefinitions().size());
    }

    @Test
    void validate_validInterceptor_passesValidation() {
        String yaml = "interceptors:\n" +
                "  - className: \"com.example.ServiceA\"\n" +
                "    before: \"" + VALID_ADVICE + "\"\n";

        WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
        assertEquals(1, config.getInterceptors().size(),
                "Valid interceptor should pass validation");
        assertEquals(1, registry.getAllDefinitions().size());
    }

    @Test
    void validate_mixedValidAndInvalid_onlyValidRegistered() {
        String yaml = "interceptors:\n" +
                "  - className: \"com.example.ServiceA\"\n" +
                "    before: \"" + VALID_ADVICE + "\"\n" +
                "  - method: \"doWork\"\n" +
                "    before: \"" + VALID_ADVICE + "\"\n" +
                "  - className: \"com.example.ServiceB\"\n" +
                "    after: \"com.nonexistent.Missing\"\n";

        WeaverConfig config = loader.loadFromReader(new StringReader(yaml), registry);
        assertEquals(1, config.getInterceptors().size(),
                "Only valid interceptor should remain after validation");
        assertEquals("com.example.ServiceA", config.getInterceptors().get(0).getClassName());
        assertEquals(1, registry.getAllDefinitions().size());
    }
}
