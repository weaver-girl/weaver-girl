package com.github.cc11001100.weavergirl.core.plugin;

/**
 * Calls {@link PluginContractLoadingTest.SharedRegistrar} from a class that is not part of the test, so a
 * registration it makes carries no frame of the test class. A plugin that declared the test class as its
 * helper must not be credited for it.
 */
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;

public class RegistrarCaller {

  /**
   * Registers once for the plugin that declared the helper and once for nobody, with no frame of the test
   * class on the stack either time. The second registration is made directly, not through the helper: the
   * helper is exactly the class the plugin declared, so a call that passed through it would be credited to
   * the plugin however little the plugin had to do with it.
   */
  public void registerBoth(WeaverPlugin scanner) throws Exception {
    scanner.getClass().getMethod("registerThroughSharedHelper").invoke(scanner);
    PluginContractLoadingTest.registry()
        .register(
            new com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition(
                "metrics-hook",
                new com.github.cc11001100.weavergirl.api.pointcut.Pointcut(
                    com.github.cc11001100.weavergirl.api.matcher.ClassMatcher.byName("x"),
                    com.github.cc11001100.weavergirl.api.matcher.MethodMatcher.any()),
                new PluginContractLoadingTest.SharedRegistrar.Noop()));
  }
}
