package com.github.cc11001100.weavergirl.core.event;

import static org.junit.jupiter.api.Assertions.*;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import org.junit.jupiter.api.Test;

/** Tests for JsonEventListener structured output. */
class JsonEventListenerTest {

  @Test
  void toJson_basicEvent_producesValidJson() {
    JsonEventListener listener = new JsonEventListener();
    InterceptorEvent event =
        InterceptorEvent.builder()
            .type("slow-query")
            .plugin("jdbc")
            .className("PgStatement")
            .methodName("execute")
            .timestamp(1717236000000L)
            .durationMs(2500)
            .build();

    String json = listener.toJson(event);

    assertTrue(json.contains("\"type\":\"slow-query\""));
    assertTrue(json.contains("\"plugin\":\"jdbc\""));
    assertTrue(json.contains("\"class\":\"PgStatement\""));
    assertTrue(json.contains("\"method\":\"execute\""));
    assertTrue(json.contains("\"timestamp\":1717236000000"));
    assertTrue(json.contains("\"durationMs\":2500"));
    assertTrue(json.startsWith("{"));
    assertTrue(json.endsWith("}"));
  }

  @Test
  void toJson_eventWithAttributes_includesAttributes() {
    JsonEventListener listener = new JsonEventListener();
    InterceptorEvent event =
        InterceptorEvent.builder()
            .type("slow-query")
            .plugin("jdbc")
            .className("PgStatement")
            .methodName("execute")
            .durationMs(1000)
            .attribute("sql", "SELECT * FROM users")
            .attribute("threshold", "1000")
            .build();

    String json = listener.toJson(event);

    assertTrue(json.contains("\"attributes\":{"));
    assertTrue(json.contains("\"sql\":\"SELECT * FROM users\""));
    assertTrue(json.contains("\"threshold\":\"1000\""));
  }

  @Test
  void toJson_specialCharactersEscaped() {
    JsonEventListener listener = new JsonEventListener();
    InterceptorEvent event =
        InterceptorEvent.builder()
            .type("error")
            .plugin("test")
            .className("Service")
            .methodName("process")
            .attribute("message", "Line1\nLine2\tTabbed\"quoted\"")
            .build();

    String json = listener.toJson(event);

    assertTrue(json.contains("\\n"));
    assertTrue(json.contains("\\t"));
    assertTrue(json.contains("\\\"quoted\\\""));
  }

  @Test
  void toJson_noAttributes_noAttributesKey() {
    JsonEventListener listener = new JsonEventListener();
    InterceptorEvent event =
        InterceptorEvent.builder()
            .type("request")
            .plugin("servlet")
            .className("FrameworkServlet")
            .methodName("service")
            .durationMs(50)
            .build();

    String json = listener.toJson(event);

    assertFalse(json.contains("attributes"));
  }

  @Test
  void onEvent_doesNotThrow() {
    JsonEventListener listener = new JsonEventListener();
    InterceptorEvent event =
        InterceptorEvent.builder()
            .type("test")
            .plugin("test")
            .className("Test")
            .methodName("test")
            .build();

    assertDoesNotThrow(() -> listener.onEvent(event));
  }
}
