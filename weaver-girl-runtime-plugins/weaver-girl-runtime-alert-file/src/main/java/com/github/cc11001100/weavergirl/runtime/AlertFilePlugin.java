package com.github.cc11001100.weavergirl.runtime;

import com.github.cc11001100.weavergirl.api.alert.AlertChannel;
import com.github.cc11001100.weavergirl.api.alert.AlertEngine;
import com.github.cc11001100.weavergirl.api.alert.AlertEvent;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Appends every alert to a file, one line each. Enabled with {@code alertFile}, the path to append
 * to.
 *
 * <p>This registers no interceptors. It adds a channel to the shared alert engine and removes that
 * same channel on shutdown, so alerts raised anywhere reach the file and a stopped plugin leaves no
 * channel behind. Writing never throws back into the engine: a channel that throws is dropped for
 * that one alert, and the failure is swallowed there.
 */
public class AlertFilePlugin implements WeaverPlugin {

  static final String NAME = "alert-file";

  private AlertFileChannel channel;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isEnabled(PluginContext context) {
    String path = context.getConfig("alertFile");
    return path != null && !path.trim().isEmpty();
  }

  @Override
  public void init(PluginContext context) {
    // init() runs before isEnabled(). Without a path there is nowhere to write.
    if (!isEnabled(context)) {
      return;
    }
    channel = new AlertFileChannel(context.getConfig("alertFile").trim());
    AlertEngine.addChannel(channel);
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // alert channel only
  }

  @Override
  public void destroy() {
    if (channel != null) {
      AlertEngine.removeChannel(channel);
      channel = null;
    }
  }

  /** Appends one alert per line. The file is opened per alert so a rotated file is picked up. */
  static final class AlertFileChannel implements AlertChannel {

    private final String path;

    AlertFileChannel(String path) {
      this.path = path;
    }

    @Override
    public void onAlert(AlertEvent alert) {
      String line =
          alert.getTimestamp()
              + " "
              + alert.getSeverity()
              + " "
              + alert.getRuleName()
              + " "
              + alert.getActualValue()
              + " "
              + sanitize(alert.getMessage())
              + System.lineSeparator();
      try (Writer writer =
          new OutputStreamWriter(
              new FileOutputStream(path, true), StandardCharsets.UTF_8)) {
        writer.write(line);
      } catch (IOException e) {
        // The engine swallows a channel that throws, which would hide the reason. Keep it quiet but
        // visible: one line on stderr, never back into the caller.
        System.err.println("[alert-file] failed to append alert to " + path + ": " + e.getMessage());
      }
    }

    /** Alerts are one line each, so a message that spans lines must not break the file. */
    private static String sanitize(String message) {
      if (message == null) {
        return "";
      }
      return message.replace('\n', ' ').replace('\r', ' ');
    }
  }
}
