package com.github.cc11001100.weavergirl.sample.search;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Records who called {@code Ledger.run}, for a service shared by several call sites.
 *
 * <p>An audit that says "the ledger ran a query" is not actionable: the reviewer needs the class,
 * method and line of the caller. That is what {@code trackCallSite} adds — the woven advice walks
 * the stack once per call and hands the caller to the interceptor. It is off by default on the
 * builder because the walk is not free, so the interceptor that reads it asks for it explicitly.
 *
 * <p>The plugin never names the application's classes anywhere but the target string, and
 * {@code com.example.search} never references the plugin.
 */
public class CallsiteTracePlugin extends AbstractPlugin {

  static final String TARGET = "com.example.search.SearchService$Ledger";

  @Override
  public String name() {
    return "callsite-trace";
  }

  @Override
  public void init(PluginContext context) {
    context.getLogger().info("callsite trace plugin initialised");
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    registry.register(
        intercept(TARGET)
            .method("run")
            .trackCallSite(true)
            .before(CallsiteTracePlugin::noteCaller)
            .name("ledger-callsite")
            .build());
  }

  private static void noteCaller(MethodInvocation invocation) {
    Class<?> caller = invocation.getCallerClass();
    String callerName = caller == null ? "?" : caller.getSimpleName();
    String line =
        callerName
            + "."
            + invocation.getCallerMethodName()
            + ":"
            + invocation.getCallerLineNumber();
    System.out.println("[callsite-trace] ledger ran, called from " + line);
    InterceptorEventPublisher.getInstance()
        .publish(
            InterceptorEvent.builder()
                .type("callsite-trace")
                .plugin("callsite-trace")
                .className("SearchService$Ledger")
                .methodName("run")
                .attribute("caller", line)
                .build());
  }
}
