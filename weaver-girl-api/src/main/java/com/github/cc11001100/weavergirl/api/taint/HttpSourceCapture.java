package com.github.cc11001100.weavergirl.api.taint;

import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.Map;

/**
 * Marks HTTP parameter, header, cookie and URL values tainted. The servlet interceptor calls {@link
 * #captureRequest(Object)}; labels name the parameter, header or cookie the value came from, or the
 * URL part ({@code http.uri}, {@code http.path}, {@code http.query}).
 *
 * @since 1.0.0
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

  public static void captureCookie(String name, String value) {
    if (name == null || value == null) {
      return;
    }
    Taint.tag(value, Taint.COOKIE_PREFIX + name);
  }

  public static void captureUri(String uri) {
    if (uri != null) {
      Taint.tag(uri, Taint.URI_LABEL);
    }
  }

  /** Reflective capture so the plugin does not compile against the servlet API. */
  public static void captureRequest(Object request) {
    if (request == null) {
      return;
    }
    captureParameters(request);
    captureHeaders(request);
    captureCookies(request);
    capturePaths(request);
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

  /** {@code getCookies()} returns the container's cookie array; each value is named by its key. */
  private static void captureCookies(Object request) {
    try {
      Method method = request.getClass().getMethod("getCookies");
      method.setAccessible(true);
      Object raw = method.invoke(request);
      if (!(raw instanceof Object[])) {
        return;
      }
      for (Object cookie : (Object[]) raw) {
        if (cookie == null) {
          continue;
        }
        Object name = read(cookie, "getName");
        Object value = read(cookie, "getValue");
        if (name instanceof String && value instanceof String) {
          captureCookie((String) name, (String) value);
        }
      }
    } catch (Exception ignored) {
      // Not a servlet request.
    }
  }

  /** Request URI, path info and raw query string: the URL itself is attacker-controlled. */
  private static void capturePaths(Object request) {
    tagIfString(request, "getRequestURI", Taint.URI_LABEL);
    tagIfString(request, "getPathInfo", Taint.PATH_LABEL);
    tagIfString(request, "getQueryString", Taint.QUERY_LABEL);
  }

  private static void tagIfString(Object request, String methodName, String label) {
    Object value = read(request, methodName);
    if (value instanceof String) {
      Taint.tag(value, label);
    }
  }

  private static Object read(Object target, String methodName) {
    try {
      Method method = target.getClass().getMethod(methodName);
      method.setAccessible(true);
      return method.invoke(target);
    } catch (Exception ignored) {
      return null;
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
