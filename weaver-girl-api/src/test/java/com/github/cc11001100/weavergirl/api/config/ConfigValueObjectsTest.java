package com.github.cc11001100.weavergirl.api.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigValueObjectsTest {

  @Test
  void changeEventReportsChangeKindAndDetails() {
    ConfigChangeEvent created = new ConfigChangeEvent("rate", null, "10", "api");
    assertEquals("rate", created.getKey());
    assertNull(created.getOldValue());
    assertEquals("10", created.getNewValue());
    assertEquals("api", created.getSource());
    assertTrue(created.getTimestamp() > 0);
    assertTrue(created.isCreation());
    assertFalse(created.isRemoval());
    assertTrue(created.toString().contains("rate"));

    ConfigChangeEvent removed = new ConfigChangeEvent("rate", "10", null, "yaml");
    assertTrue(removed.isRemoval());
    assertFalse(removed.isCreation());
  }

  @Test
  void snapshotCopiesAndProtectsConfiguration() {
    Map<String, String> source = new LinkedHashMap<>();
    source.put("one", "1");
    ConfigSnapshot snapshot = new ConfigSnapshot(3, 42, source, "before update");
    source.put("two", "2");

    assertEquals(3, snapshot.getVersion());
    assertEquals(42, snapshot.getTimestamp());
    assertEquals("before update", snapshot.getDescription());
    assertEquals("1", snapshot.get("one"));
    assertNull(snapshot.get("missing"));
    assertEquals(1, snapshot.size());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.getConfig().put("x", "y"));
    assertTrue(snapshot.toString().contains("entries=1"));
  }
}
