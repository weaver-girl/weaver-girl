package com.github.cc11001100.weavergirl.api.taint;

import java.util.List;

/**
 * Command-execution sink hook used by {@code ProcessBuilder.start} and {@code Runtime.exec}
 * advice. Observes arguments and emits a finding only when one of them is tainted. Does not start
 * a process.
 */
public final class CommandExecutionSink {

  static {
    TaintFindings.install();
  }

  private CommandExecutionSink() {}

  public static void observe(String argument) {
    TaintPropagation.observeCommand(argument);
  }

  public static void observe(String... arguments) {
    if (arguments == null) {
      return;
    }
    for (String argument : arguments) {
      observe(argument);
    }
  }

  public static void observe(List<String> command) {
    if (command == null) {
      return;
    }
    for (String argument : command) {
      observe(argument);
    }
  }
}
