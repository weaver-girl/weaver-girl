package com.github.cc11001100.weavergirl.plugins.springjms;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Spring JMS instrumentation plugin. Times one receive-and-dispatch cycle of a listener container.
 *
 * <p>{@code AbstractPollingMessageListenerContainer.receiveAndExecute} pulls one message and hands
 * it to the listener, and it keeps the same name in the javax and the jakarta generations of
 * Spring JMS, so it is matched by class name.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowMessageThreshold} — Slow message threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class SpringJmsPlugin extends AbstractPlugin {

  private long thresholdMs = 200;
  private boolean enabled = true;

  @Override
  public String name() {
    return "springjms";
  }

  @Override
  public String instrumentedLibrary() {
    return "org.springframework.jms.listener.AbstractPollingMessageListenerContainer";
  }

  @Override
  public void init(PluginContext context) {
    thresholdMs = context.getConfigLong("slowMessageThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-springjms", "springjms-error", thresholdMs);

    registry.register(
        intercept("org.springframework.jms.listener.AbstractPollingMessageListenerContainer")
            .method("receiveAndExecute")
            .around(timing::before, timing::after)
            .onException(timing::onException)
            .priority(10)
            .build());
  }
}
