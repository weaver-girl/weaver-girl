package com.example.gateway;

/**
 * A payment provider sitting behind a small gateway.
 *
 * <p>It is the stand-in for a dependency a user's service already calls. The first {@code
 * failuresBeforeRecovery} calls throw; everything after that succeeds. The gateway has no retry, no
 * timeout and no fallback of its own — it just calls through.
 */
public class Upstream {

  private final int failuresBeforeRecovery;
  private int calls;

  public Upstream(int failuresBeforeRecovery) {
    this.failuresBeforeRecovery = failuresBeforeRecovery;
  }

  /** Authorises a charge. Fails until the provider has been called enough times to recover. */
  public String authorize(long cents) {
    calls++;
    if (calls <= failuresBeforeRecovery) {
      throw new IllegalStateException("provider timed out on call " + calls);
    }
    return "approved:" + cents;
  }

  /** How many times the provider was actually called. */
  public int calls() {
    return calls;
  }
}
