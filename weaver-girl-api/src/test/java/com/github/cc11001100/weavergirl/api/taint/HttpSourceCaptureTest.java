package com.github.cc11001100.weavergirl.api.taint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Reflective HTTP capture tags parameter and header values and ignores anything else. */
class HttpSourceCaptureTest {

  @BeforeEach
  void open() {
    Taint.openScope();
  }

  @AfterEach
  void close() {
    Taint.closeScope();
  }

  @Test
  void directCaptureSkipsNullsAndTagsTheRest() {
    HttpSourceCapture.captureParameter(null, "value");
    HttpSourceCapture.captureParameter("name", null);
    HttpSourceCapture.captureHeader(null, "value");
    HttpSourceCapture.captureHeader("name", null);
    HttpSourceCapture.captureRequest(null);

    String parameter = new String("q");
    String header = new String("token");
    HttpSourceCapture.captureParameter("q", parameter);
    HttpSourceCapture.captureHeader("Authorization", header);

    assertTrue(Taint.hasSource(parameter, Taint.PARAMETER_PREFIX + "q"));
    assertTrue(Taint.hasSource(header, Taint.HEADER_PREFIX + "Authorization"));
  }

  @Test
  void aServletShapedRequestTagsArraysStringsAndHeaders() {
    String first = new String("one");
    String second = new String("two");
    String single = new String("only");
    String header = new String("secret");
    String cookie = new String("session-token");
    String uri = new String("/files/report.pdf");
    String pathInfo = new String("/../../etc/passwd");
    String query = new String("id=1");
    FakeRequest request = new FakeRequest();
    request.parameters.put("ids", new String[] {first, null, second});
    request.parameters.put("q", single);
    request.parameters.put("ignored", Integer.valueOf(1));
    request.parameters.put(null, new String[] {"skipped"});
    request.headers.put("X-Token", header);
    request.headers.put("X-Number", Integer.valueOf(7));
    request.cookies = new Object[] {new FakeCookie("sid", cookie)};
    request.uri = uri;
    request.pathInfo = pathInfo;
    request.query = query;

    HttpSourceCapture.captureRequest(request);

    assertTrue(Taint.hasSource(first, Taint.PARAMETER_PREFIX + "ids"));
    assertTrue(Taint.hasSource(second, Taint.PARAMETER_PREFIX + "ids"));
    assertTrue(Taint.hasSource(single, Taint.PARAMETER_PREFIX + "q"));
    assertTrue(Taint.hasSource(header, Taint.HEADER_PREFIX + "X-Token"));
    assertFalse(Taint.isTainted(request.headers.get("X-Number")));
    assertTrue(Taint.hasSource(cookie, Taint.COOKIE_PREFIX + "sid"));
    assertTrue(Taint.hasSource(uri, Taint.URI_LABEL));
    assertTrue(Taint.hasSource(pathInfo, Taint.PATH_LABEL));
    assertTrue(Taint.hasSource(query, Taint.QUERY_LABEL));
  }

  @Test
  void unexpectedShapesAreIgnored() {
    HttpSourceCapture.captureRequest(new Object());
    HttpSourceCapture.captureRequest(Collections.singletonMap("getParameterMap", "not-callable"));

    Map<String, String> sources = new HashMap<String, String>();
    assertEquals(0, sources.size());
    assertTrue(Taint.sources(new String("untouched")).isEmpty());
  }

  /** Only the methods the capture reads. */
  static final class FakeRequest {
    final Map<Object, Object> parameters = new HashMap<Object, Object>();
    final Map<String, Object> headers = new HashMap<String, Object>();
    Object[] cookies;
    String uri;
    String pathInfo;
    String query;

    public Map<Object, Object> getParameterMap() {
      return parameters;
    }

    public Enumeration<String> getHeaderNames() {
      return new Vector<String>(headers.keySet()).elements();
    }

    public Object getHeader(String name) {
      return headers.get(name);
    }

    public Object[] getCookies() {
      return cookies;
    }

    public String getRequestURI() {
      return uri;
    }

    public String getPathInfo() {
      return pathInfo;
    }

    public String getQueryString() {
      return query;
    }
  }

  static final class FakeCookie {
    private final String name;
    private final String value;

    FakeCookie(String name, String value) {
      this.name = name;
      this.value = value;
    }

    public String getName() {
      return name;
    }

    public String getValue() {
      return value;
    }
  }
}
