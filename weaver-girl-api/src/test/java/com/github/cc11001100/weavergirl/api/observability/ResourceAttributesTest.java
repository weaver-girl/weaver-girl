package com.github.cc11001100.weavergirl.api.observability;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResourceAttributesTest {

  @AfterEach
  void tearDown() {
    ResourceAttributes.clear();
  }

  @Test
  void laterProvidersOverrideEarlierOnes() {
    ResourceAttributes.register(provider("region", "eu-west-1", "service.name", "provider-default"));
    ResourceAttributes.register(provider("service.name", "provider-explicit"));

    Map<String, String> attributes = ResourceAttributes.getAttributes();

    assertEquals("eu-west-1", attributes.get("region"));
    assertEquals("provider-explicit", attributes.get("service.name"));
  }

  @Test
  void unregisterStopsContribution() {
    ResourceAttributeProvider provider = provider("region", "eu-west-1");
    ResourceAttributes.register(provider);
    ResourceAttributes.unregister(provider);

    assertTrue(ResourceAttributes.getAttributes().isEmpty());
  }

  @Test
  void aThrowingProviderIsSkipped() {
    ResourceAttributes.register(
        new ResourceAttributeProvider() {
          @Override
          public Map<String, String> resourceAttributes() {
            throw new IllegalStateException("no metadata service");
          }
        });
    ResourceAttributes.register(provider("region", "eu-west-1"));

    assertEquals("eu-west-1", ResourceAttributes.getAttributes().get("region"));
  }

  @Test
  void nullKeysAndValuesAreIgnored() {
    Map<String, String> attributes = new HashMap<>();
    attributes.put("region", "eu-west-1");
    attributes.put(null, "dropped");
    attributes.put("empty", "");
    attributes.put("nullValue", null);
    ResourceAttributes.register(() -> attributes);

    Map<String, String> merged = ResourceAttributes.getAttributes();
    assertEquals(Collections.singletonMap("region", "eu-west-1"), merged);
  }

  private static ResourceAttributeProvider provider(String... pairs) {
    Map<String, String> attributes = new HashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      attributes.put(pairs[i], pairs[i + 1]);
    }
    return () -> attributes;
  }
}
