package com.github.cc11001100.weavergirl.api.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for the per-method version tracker and its durable return-snapshot registry. */
class ReturnVersionTest {

  private static final String KEY = "com.example.Service.process(String,int)";

  @BeforeEach
  @AfterEach
  void resetRegistry() {
    ReturnVersion.clear();
  }

  @Test
  void next_incrementsVersionForDistinctKeys() {
    long first = ReturnVersion.next(KEY);
    long second = ReturnVersion.next(KEY);

    assertEquals(1, first);
    assertEquals(2, second);
    assertEquals(2, ReturnVersion.get(KEY), "get must return the latest version without bumping");
    assertEquals(0, ReturnVersion.get("never.used.Key"), "unknown keys read as 0");
  }

  @Test
  void get_withoutNext_returnsZero() {
    assertEquals(0, ReturnVersion.get(KEY));
  }

  @Test
  void recordSnapshot_publishesSnapshotForKey() {
    ReturnVersion.next(KEY);
    ReturnSnapshot snapshot = new ReturnSnapshot("value-1", 1, "process", String.class);

    ReturnVersion.recordSnapshot(KEY, snapshot);

    assertSame(snapshot, ReturnVersion.latestSnapshot(KEY), "latestSnapshot must return the published snapshot");
  }

  @Test
  void recordSnapshot_null_doesNotOverwriteExistingSnapshot() {
    ReturnVersion.next(KEY);
    ReturnSnapshot snapshot = new ReturnSnapshot("value-1", 1, "process", String.class);
    ReturnVersion.recordSnapshot(KEY, snapshot);

    ReturnVersion.recordSnapshot(KEY, null);

    assertSame(snapshot, ReturnVersion.latestSnapshot(KEY), "a null record must leave the last snapshot in place");
  }

  @Test
  void recordSnapshot_overwritesPreviousSnapshotForSameKey() {
    ReturnVersion.next(KEY);
    ReturnSnapshot first = new ReturnSnapshot("value-1", 1, "process", String.class);
    ReturnVersion.recordSnapshot(KEY, first);
    ReturnVersion.next(KEY);
    ReturnSnapshot second = new ReturnSnapshot("value-2", 2, "process", String.class);

    ReturnVersion.recordSnapshot(KEY, second);

    assertSame(second, ReturnVersion.latestSnapshot(KEY), "the latest record must win");
  }

  @Test
  void latestSnapshot_unknownKey_returnsNull() {
    assertNull(ReturnVersion.latestSnapshot("never.recorded.Key"));
  }

  @Test
  void clearSnapshot_removesOnlyThatKey() {
    ReturnVersion.recordSnapshot(KEY, new ReturnSnapshot("v", 1, "process", String.class));
    String other = "com.example.Other";
    ReturnVersion.recordSnapshot(other, new ReturnSnapshot("w", 1, "other", String.class));

    ReturnVersion.clearSnapshot(KEY);

    assertNull(ReturnVersion.latestSnapshot(KEY));
    assertNotNull(ReturnVersion.latestSnapshot(other), "clearSnapshot must be per-key");
  }

  @Test
  void publishedSnapshot_versionMatchesTracker() {
    long version = ReturnVersion.next(KEY);
    ReturnVersion.recordSnapshot(KEY, new ReturnSnapshot("v", version, "process", String.class));

    ReturnSnapshot snapshot = ReturnVersion.latestSnapshot(KEY);

    assertNotNull(snapshot);
    assertEquals(version, snapshot.getVersion(), "snapshot version must match the tracker value");
    assertEquals(version, ReturnVersion.get(KEY));
  }
}
