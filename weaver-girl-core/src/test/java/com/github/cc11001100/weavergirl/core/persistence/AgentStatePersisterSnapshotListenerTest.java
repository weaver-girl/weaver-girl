package com.github.cc11001100.weavergirl.core.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for AgentStatePersister.SnapshotListener.
 */
class AgentStatePersisterSnapshotListenerTest {

  @Test
  void snapshotListener_interfaceExists() {
    // SnapshotListener is an interface with default methods
    assertNotNull(AgentStatePersister.SnapshotListener.class);
  }

  @Test
  void snapshotListener_acceptsSnapshot() {
    final boolean[] called = {false};

    AgentStatePersister.SnapshotListener listener = new AgentStatePersister.SnapshotListener() {
      @Override
      public void onSnapshot(AgentStateSnapshot snapshot) {
        called[0] = true;
        assertNotNull(snapshot);
      }
    };

    Map<String, String> data = new HashMap<>();
    data.put("key", "value");
    AgentStateSnapshot snapshot = new AgentStateSnapshot(data);

    listener.onSnapshot(snapshot);

    assertTrue(called[0]);
  }

  @Test
  void snapshotListener_multipleListeners() {
    final int[] callCount = {0};

    AgentStatePersister.SnapshotListener listener1 = new AgentStatePersister.SnapshotListener() {
      @Override
      public void onSnapshot(AgentStateSnapshot snapshot) {
        callCount[0]++;
      }
    };
    AgentStatePersister.SnapshotListener listener2 = new AgentStatePersister.SnapshotListener() {
      @Override
      public void onSnapshot(AgentStateSnapshot snapshot) {
        callCount[0]++;
      }
    };

    Map<String, String> data = new HashMap<>();
    AgentStateSnapshot snapshot = new AgentStateSnapshot(data);

    listener1.onSnapshot(snapshot);
    listener2.onSnapshot(snapshot);

    assertEquals(2, callCount[0]);
  }
}
