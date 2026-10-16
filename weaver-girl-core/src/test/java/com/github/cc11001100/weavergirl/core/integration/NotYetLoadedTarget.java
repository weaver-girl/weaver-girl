package com.github.cc11001100.weavergirl.core.integration;

/**
 * A class nothing references until {@code AgentIntegrationTest} loads it after the transformer is
 * installed. It has to be its own top-level class: a nested class is loaded together with the test
 * class, which happens before the transformer exists, so it could not prove that a class is woven
 * on its first load.
 */
public class NotYetLoadedTarget {

  public String ping() {
    return "pong";
  }
}
