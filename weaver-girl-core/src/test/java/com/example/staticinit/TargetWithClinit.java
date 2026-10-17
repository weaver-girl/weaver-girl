package com.example.staticinit;

/**
 * Target class for {@code @OnStaticInit} real-weaving coverage.
 *
 * <p>Deliberately kept in a separate source file and never referenced by the test class except via
 * {@link Class#forName(String, boolean, ClassLoader)}: this class defines a real {@code <clinit>}
 * (the {@code INIT_RUNS} static field initialization) that must be woven by the transformer before
 * first active use.
 */
public final class TargetWithClinit {

  /** Set to 1 when this class's {@code <clinit>} runs. Read by the test to confirm initialization. */
  public static final int INIT_RUNS;

  static {
    INIT_RUNS = 1;
  }

  private TargetWithClinit() {
    throw new AssertionError("no instances");
  }
}
