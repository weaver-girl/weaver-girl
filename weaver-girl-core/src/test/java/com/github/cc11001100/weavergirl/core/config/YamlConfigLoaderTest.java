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
                "    before: \"com.example.TestInterceptor\"\n";

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
                "  - className: \"com.example.ServiceB\"\n" +
                "    methodPattern: \"process.*\"\n";

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
}
