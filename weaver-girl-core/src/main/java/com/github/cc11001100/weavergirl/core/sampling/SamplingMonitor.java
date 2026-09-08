package com.github.cc11001100.weavergirl.core.sampling;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically monitors invocation rate and adapts sampling. Runs a daemon thread that checks every
 * N seconds.
 */
public class SamplingMonitor {

  private static final Logger log = LoggerFactory.getLogger(SamplingMonitor.class);

  private final SamplingController controller;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private ScheduledExecutorService scheduler;
  private final AtomicLong lastCount = new AtomicLong(0);
  private long checkIntervalSeconds = 5;

  public SamplingMonitor(SamplingController controller) {
    this.controller = controller;
  }

  /** Start the monitoring thread. */
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }

    lastCount.set(controller.getInvocationCount());
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "weaver-girl-sampling-monitor");
              t.setDaemon(true);
              return t;
            });

    scheduler.scheduleAtFixedRate(
        this::check, checkIntervalSeconds, checkIntervalSeconds, TimeUnit.SECONDS);
    log.info(
        "Sampling monitor started (interval: {}s, threshold: {} inv/s)",
        checkIntervalSeconds,
        controller.getThresholdInvocationsPerSecond());
  }

  /** Stop the monitoring thread. */
  public void stop() {
    if (running.compareAndSet(true, false)) {
      if (scheduler != null) {
        scheduler.shutdown();
      }
      log.info("Sampling monitor stopped");
    }
  }

  private void check() {
    try {
      long currentCount = controller.getInvocationCount();
      long previousCount = lastCount.getAndSet(currentCount);
      long delta = currentCount - previousCount;
      double invocationsPerSecond = (double) delta / checkIntervalSeconds;
      controller.adaptRate(invocationsPerSecond);
    } catch (Exception e) {
      log.warn("Sampling monitor check failed: {}", e.getMessage());
    }
  }

  public void setCheckIntervalSeconds(long seconds) {
    this.checkIntervalSeconds = seconds;
  }
}
