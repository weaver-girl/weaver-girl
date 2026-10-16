package com.github.cc11001100.weavergirl.plugins.influxdb;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * InfluxDB instrumentation plugin. Times queries and writes.
 *
 * <p>{@code InfluxDBImpl} is the client implementation and {@code query} and {@code write} are the
 * calls that hit the server, so it is matched by exact name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowQueryThreshold} — Slow query threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class InfluxDbPlugin extends AbstractPlugin {

  private long slowQueryThresholdMs = 200;
  private boolean enabled = true;

  private static final String CLIENT = "org.influxdb.impl.InfluxDBImpl";

  @Override
  public String name() {
    return "influxdb";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.influxdb.impl.InfluxDBImpl";
  }

  @Override
  public void init(PluginContext context) {
    slowQueryThresholdMs = context.getConfigLong("slowQueryThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-influxdb", "influxdb-error", slowQueryThresholdMs);

    registry.register(
        intercept(CLIENT)
            .methodPattern("query|write")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
