package com.github.cc11001100.weavergirl.api.observability;

import com.github.cc11001100.weavergirl.api.plugin.PluginServices;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of {@link ResourceAttributeProvider}s.
 *
 * <p>Providers are consulted in registration order when a span batch is exported; a later provider
 * overrides an earlier one for the same key. Explicit configuration wins over every provider.
 *
 * @since 1.0.0
 */
public final class ResourceAttributes {

  private ResourceAttributes() {}

  /**
   * Register a provider. This is a thin wrapper over {@link PluginServices}, so a provider published
   * through {@link com.github.cc11001100.weavergirl.api.plugin.PluginContext#registerResourceAttributes}
   * disappears with its plugin.
   */
  public static void register(ResourceAttributeProvider provider) {
    if (provider != null) {
      PluginServices.register(ResourceAttributeProvider.class, provider);
    }
  }

  /** Remove a provider. */
  public static void unregister(ResourceAttributeProvider provider) {
    if (provider != null) {
      PluginServices.unregister(provider);
    }
  }

  /**
   * The merged attributes, in first-registration order for keys that first appeared in an earlier
   * provider.
   *
   * @return an immutable snapshot, never null
   */
  public static Map<String, String> getAttributes() {
    Map<String, String> merged = new LinkedHashMap<>();
    List<ResourceAttributeProvider> providers =
        PluginServices.get(ResourceAttributeProvider.class);
    for (ResourceAttributeProvider provider : providers) {
      Map<String, String> attributes;
      try {
        attributes = provider.resourceAttributes();
      } catch (RuntimeException e) {
        // A broken provider must not stop span export.
        continue;
      }
      if (attributes == null) {
        continue;
      }
      for (Map.Entry<String, String> entry : attributes.entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        if (key != null && !key.isEmpty() && value != null && !value.isEmpty()) {
          merged.put(key, value);
        }
      }
    }
    return Collections.unmodifiableMap(merged);
  }

  /** Remove every provider. Tests use this so one test does not see another's attributes. */
  public static void clear() {
    for (ResourceAttributeProvider provider :
        PluginServices.get(ResourceAttributeProvider.class)) {
      PluginServices.unregister(provider);
    }
  }
}
