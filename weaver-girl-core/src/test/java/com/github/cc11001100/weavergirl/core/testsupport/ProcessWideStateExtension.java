package com.github.cc11001100.weavergirl.core.testsupport;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Resets the process-wide singletons before <em>and</em> after every core test.
 *
 * <p>Surefire runs all of this module's tests in one JVM, and a number of engine components are
 * static singletons: the interception kill-switch, the sampling controller, emergency mode, the
 * per-call performance switches, the breaker registry. A test that changes one and does not put it
 * back makes every later weaving test fail as "before() should have been called: expected true but
 * was false" — and <em>which</em> tests fail then depends on the order surefire picked, which is
 * why this class has been a recurring source of full-suite-only failures.
 *
 * <p>Fixing it one test class at a time did not hold: a new test that forgets a reset reintroduces
 * it, and the failure appears in an unrelated class. This extension makes the reset ambient — every
 * test in the module starts from defaults, and leaves defaults behind — so a leaking test can only
 * affect itself. Assertions about a non-default value still work because the test sets the value
 * itself after the reset.
 *
 * <p>Registered globally for this module in {@code
 * src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension}. Tests that
 * deliberately keep state across methods (\#enabled, nested classes) are unaffected: the reset puts
 * the singletons back to their defaults, which is what those tests set up from anyway.
 *
 * @since 1.0.0
 */
public class ProcessWideStateExtension implements BeforeEachCallback, AfterEachCallback {

  @Override
  public void beforeEach(ExtensionContext context) {
    ProcessWideState.restore();
  }

  @Override
  public void afterEach(ExtensionContext context) {
    ProcessWideState.restore();
  }
}
