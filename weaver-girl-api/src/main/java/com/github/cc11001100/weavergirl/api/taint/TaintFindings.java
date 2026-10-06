package com.github.cc11001100.weavergirl.api.taint;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes taint findings on the public {@link InterceptorEventPublisher} path (listeners and
 * active exporters). Subscribers see {@code source}, {@code sink}, {@code argument}, and the
 * half-open {@code start}/{@code end} of that slice inside the argument.
 */
public final class TaintFindings {

  public static final String EVENT_TYPE = "iast-finding";

  private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

  private TaintFindings() {}

  public static void install() {
    if (!INSTALLED.compareAndSet(false, true)) {
      return;
    }
    TaintBridge.addObserver(
        new TaintBridge.SinkObserver() {
          @Override
          public void onFinding(
              String sourceLabel, String sinkKind, String argument, int start, int end) {
            InterceptorEventPublisher.getInstance()
                .publish(
                    InterceptorEvent.builder()
                        .type(EVENT_TYPE)
                        .plugin("iast")
                        .className(sinkKind)
                        .methodName(sinkKind)
                        .attribute("source", sourceLabel)
                        .attribute("sink", sinkKind)
                        .attribute("argument", argument)
                        .attribute("start", Integer.toString(start))
                        .attribute("end", Integer.toString(end))
                        .build());
          }
        });
  }
}
