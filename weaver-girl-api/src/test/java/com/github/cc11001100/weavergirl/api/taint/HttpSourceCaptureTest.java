package com.github.cc11001100.weavergirl.api.taint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Direct source-capture entry used by the servlet hook. */
class HttpSourceCaptureTest {

  @AfterEach
  void close() {
    Taint.closeScope();
  }

  @Test
  void captureRequestTagsParameterAndHeaderButNotAnUnmarkedValue() {
    final String parameter = new String("param-value");
    final String header = new String("header-value");
    final String unmarked = new String("not-captured");
    Map<String, String[]> parameters = new LinkedHashMap<String, String[]>();
    parameters.put("user", new String[] {parameter});
    final Map<String, String> headers = new LinkedHashMap<String, String>();
    headers.put("X-Token", header);

    Object request =
        new Object() {
          @SuppressWarnings("unused")
          public Map<String, String[]> getParameterMap() {
            return parameters;
          }

          @SuppressWarnings("unused")
          public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(headers.keySet());
          }

          @SuppressWarnings("unused")
          public String getHeader(String name) {
            return headers.get(name);
          }
        };

    Taint.openScope();
    HttpSourceCapture.captureRequest(request);

    assertTrue(Taint.hasSource(parameter, Taint.PARAMETER_PREFIX + "user"));
    assertTrue(Taint.hasSource(header, Taint.HEADER_PREFIX + "X-Token"));
    assertFalse(Taint.isTainted(unmarked));
    System.out.println(
        "IAST source "
            + Taint.PARAMETER_PREFIX
            + "user header "
            + Taint.HEADER_PREFIX
            + "X-Token");
  }
}
