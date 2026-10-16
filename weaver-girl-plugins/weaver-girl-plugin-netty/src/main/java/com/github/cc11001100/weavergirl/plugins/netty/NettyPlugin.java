package com.github.cc11001100.weavergirl.plugins.netty;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Netty instrumentation plugin. Times handler dispatch and records exceptions that escape a handler,
 * which is the failure mode a channel pipeline hides from the caller.
 *
 * <p>{@code AbstractChannelHandlerContext} is the class every handler context extends, so it is
 * matched by superclass. {@code invokeChannelRead} is the inbound dispatch and {@code
 * invokeExceptionCaught} is where an exception leaving a handler lands.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowDispatchThreshold} — Slow dispatch threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class NettyPlugin extends AbstractPlugin {

  private long slowDispatchThresholdMs = 200;
  private boolean enabled = true;

  private static final String HANDLER_CONTEXT = "io.netty.channel.AbstractChannelHandlerContext";

  @Override
  public String name() {
    return "netty";
  }

  @Override
  public String instrumentedLibrary() {
    return "io.netty.channel.AbstractChannelHandlerContext";
  }

  @Override
  public void init(PluginContext context) {
    slowDispatchThresholdMs = context.getConfigLong("slowDispatchThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-netty", "netty-error", slowDispatchThresholdMs);

    registry.register(
        interceptSubclassOf(HANDLER_CONTEXT)
            .method("invokeChannelRead")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());

    registry.register(
        interceptSubclassOf(HANDLER_CONTEXT)
            .method("invokeExceptionCaught")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
