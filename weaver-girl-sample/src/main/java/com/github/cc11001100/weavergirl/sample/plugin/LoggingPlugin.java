// weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/plugin/LoggingPlugin.java
package com.github.cc11001100.weavergirl.sample.plugin;

import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Sample plugin using the programmatic API. Demonstrates that plugin developers only need to depend
 * on weaver-girl-api.
 */
public class LoggingPlugin extends AbstractPlugin {

  @Override
  public String name() {
    return "logging-plugin";
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    InterceptorDefinition greetDef =
        intercept("com.github.cc11001100.weavergirl.sample.app.TargetService")
            .method("greet")
            .around(
                inv ->
                    System.out.println(
                        "[LOG] Before greet: " + java.util.Arrays.toString(inv.getArguments())),
                inv -> System.out.println("[LOG] After greet: returned " + inv.getReturnValue()))
            .build();
    registry.register(greetDef);

    InterceptorDefinition calcDef =
        intercept("com.github.cc11001100.weavergirl.sample.app.TargetService")
            .method("calculate")
            .around(
                inv ->
                    System.out.println(
                        "[LOG] Before calculate: "
                            + inv.getArgument(0)
                            + " + "
                            + inv.getArgument(1)),
                inv ->
                    System.out.println("[LOG] After calculate: result = " + inv.getReturnValue()))
            .build();
    registry.register(calcDef);

    InterceptorDefinition riskyDef =
        intercept("com.github.cc11001100.weavergirl.sample.app.TargetService")
            .method("riskyOperation")
            .around(
                inv -> System.out.println("[LOG] Before riskyOperation"),
                inv -> {
                  if (inv.hasException()) {
                    System.out.println(
                        "[LOG] riskyOperation threw: " + inv.getThrowable().getMessage());
                  }
                })
            .build();
    registry.register(riskyDef);
  }
}
