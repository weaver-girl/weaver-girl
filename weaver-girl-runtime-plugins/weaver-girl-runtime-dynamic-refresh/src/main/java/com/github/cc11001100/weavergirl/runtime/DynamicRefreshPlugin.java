package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refreshes the interceptor registry on a fixed interval, so interceptors registered or removed after
 * startup take effect on classes that are already loaded. Enabled with {@code refreshIntervalSeconds}
 * set above zero.
 *
 * <p>This registers no interceptors. Each tick drops the cached class-to-interceptor snapshot and
 * retransforms already-loaded classes through the hook the agent installs on {@link InterceptorHolder}.
 * The hook is read on every tick rather than once at startup: the agent installs it only after bootstrap
 * returns, which is after this plugin's {@code init} has already run.
 */
public class DynamicRefreshPlugin implements WeaverPlugin {

  static final String NAME = "dynamic-refresh";

  private static final Logger log = LoggerFactory.getLogger(DynamicRefreshPlugin.class);

  private ScheduledExecutorService scheduler;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    return intervalSeconds(context) > 0;
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(). With no interval set there is nothing to schedule.
    long intervalSeconds = intervalSeconds(context);
    if (intervalSeconds <= 0) {
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r, "weaver-girl-dynamic-refresh");
              thread.setDaemon(true);
              return thread;
            });
    scheduler.scheduleAtFixedRate(
        new RefreshTask(), intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    log.info("Dynamic auto-refresh enabled: interval={}s", intervalSeconds);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // refreshes the registry, registers nothing of its own
  }

  @Override
  public void destroy() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
  }

  /** The configured interval, or 0 when it is absent, not a number, or not positive. */
  private static long intervalSeconds(PluginContext context) {
    String raw = context.getConfig("refreshIntervalSeconds");
    if (raw == null || raw.trim().isEmpty()) {
      return 0;
    }
    try {
      long interval = Long.parseLong(raw.trim());
      return interval > 0 ? interval : 0;
    } catch (NumberFormatException e) {
      log.warn(
          "Invalid refreshIntervalSeconds '{}', skipping auto-refresh", raw);
      return 0;
    }
  }

  /** One refresh: drop the cached snapshot, then retransform whatever is already loaded. */
  static final class RefreshTask implements Runnable {
    @Override
    public void run() {
      try {
        InterceptorHolder.invalidateSnapshot();
        Runnable retransform = InterceptorHolder.getRetransformHook();
        if (retransform != null) {
          retransform.run();
        }
      } catch (Throwable t) {
        log.warn("[DynamicRefresh] Scheduled refresh failed: {}", t.getMessage());
      }
    }
  }
}
