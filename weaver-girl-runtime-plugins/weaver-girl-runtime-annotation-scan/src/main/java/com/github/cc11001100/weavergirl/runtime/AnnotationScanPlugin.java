package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.scanner.AnnotatedClassScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads {@code @WeaveClass} interceptors from the packages named by {@code annotationPackages}
 * (semicolon-separated). Absent that config there is nothing to scan.
 */
public class AnnotationScanPlugin implements WeaverPlugin {

  private static final Logger log = LoggerFactory.getLogger(AnnotationScanPlugin.class);

  static final String NAME = "annotation-scan";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    String packages = context.getConfig("annotationPackages");
    return packages != null && !packages.trim().isEmpty();
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // scanning needs the config, so it happens in init
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(), so an agent that never asked for annotation scanning reaches
    // here with nothing to scan. There is nothing to do until the plugin is actually enabled.
    if (!isEnabled(context)) {
      return;
    }
    String packages = context.getConfig("annotationPackages");
    String[] split = packages.split(";");
    String[] trimmed = new String[split.length];
    for (int i = 0; i < split.length; i++) {
      trimmed[i] = split[i].trim();
    }
    int found = new AnnotatedClassScanner().scanAndLoad(context.getRegistry(), trimmed);
    log.info("Scanned {} annotation package(s), loaded {} @WeaveClass interceptors", split.length, found);
  }
}
