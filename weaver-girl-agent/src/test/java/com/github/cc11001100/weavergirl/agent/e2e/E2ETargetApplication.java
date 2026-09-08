package com.github.cc11001100.weavergirl.agent.e2e;

/**
 * Simple target application for E2E testing. Outputs method calls to stdout so the test can verify
 * interception.
 */
public class E2ETargetApplication {
  public static void main(String[] args) {
    E2ETargetApplication app = new E2ETargetApplication();
    String result = app.greet("World");
    System.out.println("RESULT: " + result);

    // Signal completion
    System.out.println("E2E_COMPLETE");
  }

  public String greet(String name) {
    return "Hello, " + name;
  }
}
