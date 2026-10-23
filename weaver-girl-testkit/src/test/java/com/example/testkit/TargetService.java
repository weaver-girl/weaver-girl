package com.example.testkit;

/** Plain target class the harness weaves in its own tests. */
public class TargetService {

  public String secret = "original";

  public String greet(String name) {
    return "hello " + name;
  }

  public String getSecret() {
    return secret;
  }

  public void setSecret(String value) {
    this.secret = value;
  }

  public String fail() {
    throw new IllegalStateException("boom");
  }
}
