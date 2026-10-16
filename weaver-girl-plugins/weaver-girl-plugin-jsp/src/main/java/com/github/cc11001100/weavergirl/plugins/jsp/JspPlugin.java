package com.github.cc11001100.weavergirl.plugins.jsp;

import com.github.cc11001100.weavergirl.api.interceptor.TimingInterceptor;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * JSP instrumentation plugin. Times a compiled page rendering.
 *
 * <p>{@code HttpJspPage} is the interface every compiled page implements and {@code _jspService} is
 * the method the container calls to render it, so it is matched by interface. Both the {@code
 * javax} and {@code jakarta} servlet packages are covered.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowRenderThreshold} — Slow render threshold in ms (default: 200)
 *   <li>{@code enabled} — Enable/disable (default: true)
 * </ul>
 */
public class JspPlugin extends AbstractPlugin {

  private long slowRenderThresholdMs = 200;
  private boolean enabled = true;

  private static final String[] PAGES = {
    "javax.servlet.jsp.HttpJspPage", "jakarta.servlet.jsp.HttpJspPage"
  };

  @Override
  public String name() {
    return "jsp";
  }

  @Override
  public String instrumentedLibrary() {
    return "javax.servlet.jsp.HttpJspPage";
  }

  @Override
  public void init(PluginContext context) {
    slowRenderThresholdMs = context.getConfigLong("slowRenderThreshold", 200);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    if (!enabled) return;

    TimingInterceptor timing =
        new TimingInterceptor(name(), "slow-jsp", "jsp-error", slowRenderThresholdMs);

    for (String page : PAGES) {
      registry.register(
          interceptImplementing(page)
              .method("_jspService")
              .around(timing::before, timing::after)
              .onException(timing::onException)
              .priority(10)
              .build());
    }
  }
}
