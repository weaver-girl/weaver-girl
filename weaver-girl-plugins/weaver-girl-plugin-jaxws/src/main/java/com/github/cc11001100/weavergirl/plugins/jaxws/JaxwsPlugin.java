package com.github.cc11001100.weavergirl.plugins.jaxws;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * JAX-WS instrumentation plugin. Times a provider handling a request.
 *
 * <p>{@code Provider} is the interface a web service endpoint implements and {@code invoke} is the
 * method the runtime calls with the request payload, so it is matched by interface. Both the {@code
 * javax} and {@code jakarta} packages are covered, since an application uses one or the other.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRequestThreshold} — Slow request threshold in ms (default: 500)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class JaxwsPlugin extends AbstractPlugin {

  private long slowRequestThresholdMs = 500;
  private boolean enabled = true;

  private static final String[] PROVIDERS = {"javax.xml.ws.Provider", "jakarta.xml.ws.Provider"};

  @Override
  public String name() {
    return "jaxws";
  }

  @Override
  public String instrumentedLibrary() {
    return "javax.xml.ws.Provider";
  }

  @Override
  public void init(PluginContext context) {
    slowRequestThresholdMs = context.getConfigLong("slowRequestThreshold", 500);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-jaxws", "jaxws-error", slowRequestThresholdMs);

    for (String provider : PROVIDERS) {
      registry.register(
          interceptImplementing(provider)
              .method("invoke")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
