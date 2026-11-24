package com.github.cc11001100.weavergirl.api.metrics;

/**
 * A low-resolution wall clock for counters that only need "roughly when".
 *
 * <p>{@link System#currentTimeMillis()} costs about 20 ns per call on this class of machine. The
 * per-interceptor metrics record it twice per hook callback purely to populate "last invocation at"
 * / "last error at" for health reporting — a field read by a management thread every 30 s. Paying a
 * 20 ns clock read per callback for a timestamp with second-level relevance is wasted work on the
 * instrumented hot path.
 *
 * <p>This class keeps a single {@code volatile} timestamp refreshed by a daemon thread every
 * {@link #TICK_MILLIS}. Readers pay one volatile read instead of a clock read, and the timestamp is
 * accurate to the tick. Nothing in the agent requires exact per-call timestamps: the counters that
 * do (durations) use {@link System#nanoTime()} directly at the call site.
 *
 * <p>The updater thread is started on first use and is a daemon, so it never keeps the JVM alive
 * and costs one sleeping thread.
 *
 * @since 2.0.0
 */
public final class CoarseClock {

  /** How often the cached timestamp is refreshed, in milliseconds. */
  static final long TICK_MILLIS = 10;

  /**
   * Cached wall-clock time in millis. Seeded at class-initialisation so a first read (which can
   * happen before the updater thread's first tick) is never 0.
   */
  private static volatile long millis = System.currentTimeMillis();

  private static volatile boolean started;

  private CoarseClock() {}

  /**
   * The cached wall-clock time, within {@link #TICK_MILLIS} of the real time.
   *
   * @return milliseconds since the epoch
   */
  public static long millis() {
    if (!started) {
      startUpdater();
    }
    return millis;
  }

  /** Starts the daemon refresher, once. A failure to start degrades to the seeded value. */
  private static void startUpdater() {
    synchronized (CoarseClock.class) {
      if (started) {
        return;
      }
      try {
        Thread updater =
            new Thread(
                () -> {
                  while (true) {
                    millis = System.currentTimeMillis();
                    try {
                      Thread.sleep(TICK_MILLIS);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                  }
                },
                "weaver-girl-coarse-clock");
        updater.setDaemon(true);
        updater.start();
        started = true;
      } catch (Throwable e) {
        // A thread cannot be started (security manager, exhausted threads): staying with the
        // seeded value stale is still readable and never breaks the caller.
        started = true;
      }
    }
  }
}
