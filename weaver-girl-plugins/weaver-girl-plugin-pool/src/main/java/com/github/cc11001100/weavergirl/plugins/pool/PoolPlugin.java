package com.github.cc11001100.weavergirl.plugins.pool;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Apache Commons Pool instrumentation plugin. Times borrowing an object from a generic pool.
 *
 * <p>{@code GenericObjectPool} and {@code GenericKeyedObjectPool} are the two pool implementations
 * and {@code borrowObject} is the call that blocks while the pool is exhausted, so both are matched
 * by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowBorrowThreshold} — Slow borrow threshold in ms (default: 100)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class PoolPlugin extends AbstractPlugin {

  private long slowBorrowThresholdMs = 100;
  private boolean enabled = true;

  private static final String[] POOLS = {
    "org.apache.commons.pool2.impl.GenericObjectPool",
    "org.apache.commons.pool2.impl.GenericKeyedObjectPool"
  };

  @Override
  public String name() {
    return "pool";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.apache.commons.pool2.impl.GenericObjectPool";
  }

  @Override
  public void init(PluginContext context) {
    slowBorrowThresholdMs = context.getConfigLong("slowBorrowThreshold", 100);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-pool", "pool-error", slowBorrowThresholdMs);

    for (String pool : POOLS) {
      registry.register(
          intercept(pool)
              .method("borrowObject")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
