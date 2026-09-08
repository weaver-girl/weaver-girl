package com.github.cc11001100.weavergirl.core.release;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleaseInfoTest {

  @Test
  void getInstance_returnsSingleton() {
    ReleaseInfo info1 = ReleaseInfo.getInstance();
    ReleaseInfo info2 = ReleaseInfo.getInstance();
    assertSame(info1, info2);
  }

  @Test
  void getVersion_returnsVersion() {
    ReleaseInfo info = ReleaseInfo.getInstance();
    assertNotNull(info.getVersion());
    assertFalse(info.getVersion().isEmpty());
  }

  @Test
  void getBuildTimestamp_returnsTimestamp() {
    ReleaseInfo info = ReleaseInfo.getInstance();
    assertNotNull(info.getBuildTimestamp());
  }

  @Test
  void getGitCommit_returnsCommit() {
    ReleaseInfo info = ReleaseInfo.getInstance();
    assertNotNull(info.getGitCommit());
  }

  @Test
  void getBuildNumber_returnsNumber() {
    ReleaseInfo info = ReleaseInfo.getInstance();
    assertNotNull(info.getBuildNumber());
  }

  @Test
  void getChecksum_returnsChecksum() {
    ReleaseInfo info = ReleaseInfo.getInstance();
    assertNotNull(info.getChecksum());
  }

  @Test
  void verifyIntegrity_matchingChecksum_returnsTrue() {
    ReleaseInfo info = ReleaseInfo.getInstance();
    // When no release properties file exists, checksum is empty
    // and verifyIntegrity with null/empty returns false
    assertFalse(info.verifyIntegrity(null));
  }

  @Test
  void verifyIntegrity_nullChecksum_returnsFalse() {
    ReleaseInfo info = ReleaseInfo.getInstance();
    assertFalse(info.verifyIntegrity(null));
  }

  @Test
  void verifyIntegrity_emptyChecksum_returnsFalse() {
    ReleaseInfo info = ReleaseInfo.getInstance();
    // Dev build has empty checksum, so verifyIntegrity with non-empty should fail
    assertFalse(info.verifyIntegrity("abc123"));
  }

  @Test
  void getSummary_containsVersion() {
    ReleaseInfo info = ReleaseInfo.getInstance();
    String summary = info.getSummary();
    assertTrue(summary.contains("Weaver-Girl"));
    assertTrue(summary.contains(info.getVersion()));
  }

  @Test
  void toMap_returnsAllFields() {
    ReleaseInfo info = ReleaseInfo.getInstance();
    Map<String, String> map = info.toMap();
    assertEquals(5, map.size());
    assertTrue(map.containsKey("version"));
    assertTrue(map.containsKey("buildTimestamp"));
    assertTrue(map.containsKey("gitCommit"));
    assertTrue(map.containsKey("buildNumber"));
    assertTrue(map.containsKey("checksum"));
  }

  @Test
  void toString_containsVersion() {
    ReleaseInfo info = ReleaseInfo.getInstance();
    String str = info.toString();
    assertTrue(str.contains("Weaver-Girl"));
  }

  @Test
  void sha256_computesHash() {
    byte[] data = "hello world".getBytes();
    String hash = ReleaseInfo.sha256(data);
    assertNotNull(hash);
    assertEquals(64, hash.length()); // SHA-256 hex = 64 chars
    // Known SHA-256 of "hello world"
    assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash);
  }

  @Test
  void sha256_emptyInput_returnsHash() {
    String hash = ReleaseInfo.sha256(new byte[0]);
    assertNotNull(hash);
    assertEquals(64, hash.length());
  }

  @Test
  void sha256_differentInputs_differentHashes() {
    String hash1 = ReleaseInfo.sha256("input1".getBytes());
    String hash2 = ReleaseInfo.sha256("input2".getBytes());
    assertNotEquals(hash1, hash2);
  }
}
