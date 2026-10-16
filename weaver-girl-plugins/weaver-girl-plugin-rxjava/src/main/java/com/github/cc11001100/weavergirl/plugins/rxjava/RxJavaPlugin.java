package com.github.cc11001100.weavergirl.plugins.rxjava;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * RxJava instrumentation plugin. Times subscription, which is the moment a cold source starts its
 * work.
 *
 * <p>Both RxJava 2 ({@code io.reactivex}) and RxJava 3 ({@code io.reactivex.rxjava3}) are covered,
 * because an application uses one or the other. {@code Observable} and {@code Flowable} are abstract
 * classes every source extends, so they are matched by superclass, and {@code subscribeActual} is the
 * method {@code subscribe} delegates the real work to.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowSubscribeThreshold} — Slow subscribe threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class RxJavaPlugin extends AbstractPlugin {

  private long slowSubscribeThresholdMs = 200;
  private boolean enabled = true;

  private static final String[] SOURCES = {
    "io.reactivex.Observable",
    "io.reactivex.Flowable",
    "io.reactivex.rxjava3.core.Observable",
    "io.reactivex.rxjava3.core.Flowable"
  };

  @Override
  public String name() {
    return "rxjava";
  }

  @Override
  public String instrumentedLibrary() {
    return "io.reactivex.Observable";
  }

  @Override
  public void init(PluginContext context) {
    slowSubscribeThresholdMs = context.getConfigLong("slowSubscribeThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-rxjava", "rxjava-error", slowSubscribeThresholdMs);

    for (String source : SOURCES) {
      registry.register(
          interceptSubclassOf(source)
              .method("subscribeActual")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
