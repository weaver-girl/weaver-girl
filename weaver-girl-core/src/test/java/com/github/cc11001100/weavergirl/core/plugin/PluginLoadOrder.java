package com.github.cc11001100.weavergirl.core.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records the order in which generated test plugins initialise.
 *
 * <p>Lives in its own class because those plugins are compiled at test runtime, and the class they
 * call back into has to be on the compiler's classpath — which the test class itself is not, since
 * it is still being compiled when the compiler runs.
 */
public final class PluginLoadOrder {

  /** Plugin names, in the order their {@code init} ran. */
  public static final List<String> ORDER = Collections.synchronizedList(new ArrayList<>());

  private PluginLoadOrder() {}
}
