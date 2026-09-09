package com.github.cc11001100.weavergirl.core.event;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;

/**
 * Structured lifecycle events for interceptor registry mutations and plugin lifecycle transitions.
 *
 * <p>Events are emitted synchronously on the calling thread; listeners should be fast and non-blocking.
 * Failures in listeners are caught by the publisher and do not affect the operation being observed.
 */
public final class LifecycleEvents {

  private LifecycleEvents() {}

  public static final String PHASE_INIT = "init";
  public static final String PHASE_REGISTER_INTERCEPTORS = "registerInterceptors";
  public static final String PHASE_DESTROY = "destroy";
  public static final String PHASE_ENABLE = "enable";
  public static final String PHASE_DISABLE = "disable";
  public static final String PHASE_UNLOAD = "unload";
  public static final String PHASE_REGISTER = "register";
  public static final String PHASE_UNREGISTER = "unregister";
  public static final String PHASE_CLEAR = "clear";
  public static final String PHASE_EMERGENCY = "emergency";
  public static final String PHASE_SHUTDOWN = "shutdown";

  /**
   * Build an interceptor-registry lifecycle event.
   *
   * @param phase one of the PHASE_* constants
   * @param name the interceptor definition name
   * @param success whether the operation succeeded
   * @param detail optional human-readable detail, may be null
   */
  public static InterceptorEvent registry(String phase, String name, boolean success, String detail) {
    return InterceptorEvent.builder()
        .type("weaver-girl.lifecycle.registry")
        .plugin(name)
        .className(name)
        .methodName(phase)
        .attribute("phase", phase)
        .attribute("success", String.valueOf(success))
        .attribute("detail", detail != null ? detail : "")
        .build();
  }

  /**
   * Build a plugin lifecycle event.
   *
   * @param phase one of the PHASE_* constants
   * @param pluginName the plugin name
   * @param success whether the operation succeeded
   * @param detail optional human-readable detail, may be null
   */
  public static InterceptorEvent plugin(String phase, String pluginName, boolean success, String detail) {
    return InterceptorEvent.builder()
        .type("weaver-girl.lifecycle.plugin")
        .plugin(pluginName)
        .className(pluginName)
        .methodName(phase)
        .attribute("phase", phase)
        .attribute("success", String.valueOf(success))
        .attribute("detail", detail != null ? detail : "")
        .build();
  }
}
