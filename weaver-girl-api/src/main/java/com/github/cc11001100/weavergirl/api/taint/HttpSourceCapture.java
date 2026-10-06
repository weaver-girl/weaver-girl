package com.github.cc11001100.weavergirl.api.taint;

import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.Map;

/**
 * Marks HTTP parameter and header values tainted. The servlet interceptor calls {@link
 * #captureRequest(Object)}; labels name the parameter or header the value came from.
 */
public final class HttpSourceCapture {

  private HttpSourceCapture() {}

  public static void captureParameter(String name, String value) {
    if (name == null || value == null) {
      return;
    }
    Taint.tag(value, Taint.PARAMETER_PREFIX + name);
  }

  public static void captureHeader(String name, String value) {
    if (name == null || value == null) {
      return;
    }
    Taint.tag(value, Taint.HEADER_PREFIX + name);
  }

  /** Reflective capture so the plugin does not compile against the servlet API. */
  public static void captureRequest(Object request) {
    if (request == null) {
      return;
    }
    captureParameters(request);
    captureHeaders(request);
  }

  private static void captureParameters(Object request) {
    try {
      Method method = request.getClass().getMethod("getParameterMap");
      method.setAccessible(true);
      Object raw = method.invoke(request);
      if (!(raw instanceof Map)) {
        return;
      }
      Map<?, ?> map = (Map<?, ?>) raw;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() == null) {
          continue;
        }
        String name = String.valueOf(entry.getKey());
        Object value = entry.getValue();
        if (value instanceof String[]) {
          String[] values = (String[]) value;
          for (String item : values) {
            captureParameter(name, item);
          }
        } else if (value instanceof String) {
          captureParameter(name, (String) value);
        }
      }
    } catch (Exception ignored) {
      // Not a servlet request.
    }
  }

  private static void captureHeaders(Object request) {
    try {
      Method namesMethod = request.getClass().getMethod("getHeaderNames");
      namesMethod.setAccessible(true);
      Object rawNames = namesMethod.invoke(request);
      if (!(rawNames instanceof Enumeration)) {
        return;
      }
      Method headerMethod = request.getClass().getMethod("getHeader", String.class);
      headerMethod.setAccessible(true);
      Enumeration<?> names = (Enumeration<?>) rawNames;
      while (names.hasMoreElements()) {
        Object nameObject = names.nextElement();
        if (!(nameObject instanceof String)) {
          continue;
        }
        String name = (String) nameObject;
        Object value = headerMethod.invoke(request, name);
        if (value instanceof String) {
          captureHeader(name, (String) value);
        }
      }
    } catch (Exception ignored) {
      // Not a servlet request.
    }
  }
}
