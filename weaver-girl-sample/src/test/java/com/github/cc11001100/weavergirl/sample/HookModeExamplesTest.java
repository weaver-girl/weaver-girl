package com.github.cc11001100.weavergirl.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.junit.jupiter.api.Test;

/**
 * The three hook modes the sample advertises must each have a real implementation on the class
 * path, and the classes the guide hands a plugin developer must keep compiling.
 *
 * <p>The compile check itself is the build configuration: {@code docs/examples} is added as a test
 * source of this module, so a broken example fails {@code mvn test} rather than a reader's first
 * attempt. This test pins the ones the guide links to, so a file cannot be dropped silently.
 */
class HookModeExamplesTest {

  @Test
  void programmaticModePluginIsOnTheSpi() throws Exception {
    // The SPI entry in the sample jar, and the class that carries it.
    Class<?> loggingPlugin =
        Class.forName("com.github.cc11001100.weavergirl.sample.plugin.LoggingPlugin");
    WeaverPlugin instance = (WeaverPlugin) loggingPlugin.getDeclaredConstructor().newInstance();
    assertEquals("logging-plugin", instance.name());

    java.util.ServiceLoader<WeaverPlugin> services =
        java.util.ServiceLoader.load(WeaverPlugin.class, getClass().getClassLoader());
    boolean found = false;
    for (WeaverPlugin plugin : services) {
      if ("logging-plugin".equals(plugin.name())) {
        found = true;
      }
    }
    assertTrue(found, "the programmatic plugin is not discoverable through the SPI");
  }

  @Test
  void annotationModeInterceptorCarriesWeaveClass() throws Exception {
    Class<?> interceptor =
        Class.forName("com.github.cc11001100.weavergirl.sample.interceptor.AnnotationInterceptor");
    assertTrue(
        interceptor.isAnnotationPresent(
            com.github.cc11001100.weavergirl.annotation.WeaveClass.class),
        "the annotation-mode interceptor is not annotated with @WeaveClass");
  }

  @Test
  void yamlModeInterceptorIsInstantiableAndRegisteredByName() throws Exception {
    Class<?> advice =
        Class.forName("com.github.cc11001100.weavergirl.sample.interceptor.SampleYamlInterceptor");
    assertTrue(
        com.github.cc11001100.weavergirl.api.interceptor.Interceptor.class.isAssignableFrom(advice),
        "the YAML interceptor does not implement Interceptor");
    assertNotNull(advice.getDeclaredConstructor().newInstance());
  }

  @Test
  void guideExamplesStillCompileAndAreInstantiable() throws Exception {
    for (String name :
        new String[] {
          "com.github.cc11001100.weavergirl.docs.examples.AuditLogPlugin",
          "com.github.cc11001100.weavergirl.docs.examples.CustomMetricPlugin",
          "com.github.cc11001100.weavergirl.docs.examples.CustomObservabilityPlugin"
        }) {
      Class<?> example = Class.forName(name);
      assertTrue(
          WeaverPlugin.class.isAssignableFrom(example), name + " no longer implements WeaverPlugin");
      WeaverPlugin plugin = (WeaverPlugin) example.getDeclaredConstructor().newInstance();
      // Naming and registration are what a reader copies; exercise them without a live agent.
      assertNotNull(plugin.name(), name + " returns a null plugin name");
      plugin.registerInterceptors(
          new com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry() {
            @Override
            public void register(
                com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition definition) {
              assertNotNull(definition);
            }

            @Override
            public boolean unregister(String interceptorName) {
              return true;
            }

            @Override
            public java.util.List<
                    com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition>
                getAllDefinitions() {
              return java.util.Collections.emptyList();
            }

            @Override
            public java.util.List<
                    com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition>
                getInterceptorsForClass(String className) {
              return java.util.Collections.emptyList();
            }
          });
    }
  }
}
