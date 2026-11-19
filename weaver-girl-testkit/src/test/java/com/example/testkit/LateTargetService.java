package com.example.testkit;

/** Second plain target class, for the "registered after install" regression test. */
public class LateTargetService {

  public String touch(String value) {
    return value + " touched";
  }
}
