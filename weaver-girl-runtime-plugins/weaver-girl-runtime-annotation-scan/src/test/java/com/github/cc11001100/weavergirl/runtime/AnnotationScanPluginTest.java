package com.github.cc11001100.weavergirl.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Annotation scanning switches on only when {@code annotationPackages} names something. */
class AnnotationScanPluginTest {

  @Test
  void disabledWithoutPackages() {
    assertFalse(new AnnotationScanPlugin().isEnabled(context(Collections.emptyMap())));
  }

  @Test
  void disabledWhenPackagesBlank() {
    assertFalse(new AnnotationScanPlugin().isEnabled(context(config("annotationPackages", "  "))));
  }

  @Test
  void enabledWhenPackagesSet() {
    assertTrue(
        new AnnotationScanPlugin()
            .isEnabled(context(config("annotationPackages", "com.example.hooks"))));
  }

  @Test
  void emptyPackageLoadsNothing() {
    AnnotationScanPlugin plugin = new AnnotationScanPlugin();
    InterceptorRegistry registry = registry();
    PluginContext context = context(config("annotationPackages", "com.example.nothing.here"), registry);

    plugin.init(context);

    assertEquals(0, registry.getAllDefinitions().size());
  }

  private static Map<String, String> config(String key, String value) {
    Map<String, String> config = new HashMap<>();
    config.put(key, value);
    return config;
  }

  private static PluginContext context(Map<String, String> config) {
    return context(config, registry());
  }

  private static PluginContext context(Map<String, String> config, InterceptorRegistry registry) {
    return new com.github.cc11001100.weavergirl.core.plugin.DefaultPluginContext(
        registry, config, AnnotationScanPlugin.NAME);
  }

  private static InterceptorRegistry registry() {
    return new com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry();
  }
}
