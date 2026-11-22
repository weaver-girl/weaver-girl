package com.example.account;

/**
 * An export job that fails on a provider that is not reachable.
 *
 * <p>The job has no error handling: {@code run} throws, and its caller would have to know what to
 * do about it. Under the agent the catch hook answers every failure with a degraded report, so the
 * caller gets an {@code "unavailable"} result instead of an exception — without this class gaining
 * a try/catch.
 */
public class ExportJob {

  private final boolean providerUp;

  public ExportJob(boolean providerUp) {
    this.providerUp = providerUp;
  }

  /**
   * Exports the ledger.
   *
   * <p>Throws when the provider is down. Nothing here catches it.
   *
   * @return the export location
   * @throws IllegalStateException when the provider cannot be reached
   */
  public String run() {
    if (!providerUp) {
      throw new IllegalStateException("export provider unreachable");
    }
    return "s3://ledger/nightly.csv";
  }
}
