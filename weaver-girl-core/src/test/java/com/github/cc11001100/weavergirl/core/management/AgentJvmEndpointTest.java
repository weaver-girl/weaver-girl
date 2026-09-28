package com.github.cc11001100.weavergirl.core.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** GET /jvm returns the management beans at request time. */
class AgentJvmEndpointTest {

  private AgentApiServer server;
  private int port;

  @BeforeEach
  void setUp() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    server = new AgentApiServer(port);
    server.start();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void jvmEndpointMatchesManagementBeans() throws Exception {
    String first = get("/jvm");
    String second = get("/jvm");
    System.out.println("JVM-HTTP-1 " + first);
    System.out.println("JVM-HTTP-2 " + second);
    assertSnapshot(first);
    assertSnapshot(second);
    assertGcEqualsFreshRead();
  }

  /**
   * Names, collection counts, and collection times must equal a ManagementFactory read taken after
   * the response. A collection between those two reads fails the comparison, so the route is fetched
   * again until one body matches the beans read immediately afterwards.
   */
  private void assertGcEqualsFreshRead() throws Exception {
    AssertionError last = null;
    for (int attempt = 0; attempt < 8; attempt++) {
      String body = get("/jvm");
      try {
        assertGcEqualsBeans(body);
        System.out.println("JVM-HTTP-AGREED " + body);
        return;
      } catch (AssertionError failure) {
        last = failure;
      }
    }
    throw last;
  }

  private void assertGcEqualsBeans(String body) {
    Map<String, Object> json = Strict.parseObject(body);
    List<Object> collectors = (List<Object>) json.get("garbageCollectors");
    List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
    assertEquals(beans.size(), collectors.size());
    for (Object item : collectors) {
      Map<String, Object> row = (Map<String, Object>) item;
      String name = (String) row.get("name");
      long count = ((Number) row.get("collectionCount")).longValue();
      long time = ((Number) row.get("collectionTimeMs")).longValue();
      GarbageCollectorMXBean bean = find(name);
      assertEquals(bean.getName(), name);
      assertEquals(bean.getCollectionCount(), count, name);
      assertEquals(bean.getCollectionTime(), time, name);
    }
  }

  private void assertSnapshot(String body) {
    Map<String, Object> json = Strict.parseObject(body);
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    long heap = ((Number) json.get("heapUsed")).longValue();
    long nonHeap = ((Number) json.get("nonHeapUsed")).longValue();
    long threads = ((Number) json.get("threadCount")).longValue();
    assertTrue(heap >= 0);
    assertTrue(nonHeap >= 0);
    assertTrue(threads > 0);
    long beanHeap = memory.getHeapMemoryUsage().getUsed();
    long beanNonHeap = memory.getNonHeapMemoryUsage().getUsed();
    long beanThreads = ManagementFactory.getThreadMXBean().getThreadCount();
    assertTrue(Math.abs(heap - beanHeap) < 64L * 1024L * 1024L);
    assertTrue(Math.abs(nonHeap - beanNonHeap) < 64L * 1024L * 1024L);
    assertTrue(Math.abs(threads - beanThreads) <= 32);

    List<Object> collectors = (List<Object>) json.get("garbageCollectors");
    assertEquals(ManagementFactory.getGarbageCollectorMXBeans().size(), collectors.size());
    for (Object item : collectors) {
      Map<String, Object> row = (Map<String, Object>) item;
      String name = (String) row.get("name");
      long count = ((Number) row.get("collectionCount")).longValue();
      long time = ((Number) row.get("collectionTimeMs")).longValue();
      assertTrue(count >= 0, name);
      assertTrue(time >= 0, name);
      assertEquals(name, find(name).getName());
      System.out.println(
          "JVM-HTTP gc name="
              + name
              + " collectionCount="
              + count
              + " collectionTimeMs="
              + time);
    }
    System.out.println(
        "JVM-HTTP heapUsed=" + heap + " nonHeapUsed=" + nonHeap + " threadCount=" + threads);
  }

  private static GarbageCollectorMXBean find(String name) {
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (name.equals(bean.getName())) {
        return bean;
      }
    }
    throw new AssertionError("missing collector " + name);
  }

  private String get(String path) throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
    connection.setRequestMethod("GET");
    assertEquals(200, connection.getResponseCode());
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
    StringBuilder body = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      body.append(line);
    }
    reader.close();
    return body.toString();
  }

  /** Minimal parser for the JVM snapshot document. */
  static final class Strict {
    private final String text;
    private int index;

    private Strict(String text) {
      this.text = text;
    }

    static Map<String, Object> parseObject(String text) {
      Strict parser = new Strict(text);
      Map<String, Object> value = parser.object();
      parser.skip();
      if (parser.index != text.length()) {
        throw new IllegalArgumentException("trailing");
      }
      return value;
    }

    private Map<String, Object> object() {
      expect('{');
      Map<String, Object> map = new LinkedHashMap<String, Object>();
      skip();
      if (peek('}')) {
        index++;
        return map;
      }
      while (true) {
        String key = string();
        skip();
        expect(':');
        map.put(key, value());
        skip();
        if (peek('}')) {
          index++;
          return map;
        }
        expect(',');
      }
    }

    private List<Object> array() {
      expect('[');
      List<Object> list = new ArrayList<Object>();
      skip();
      if (peek(']')) {
        index++;
        return list;
      }
      while (true) {
        list.add(value());
        skip();
        if (peek(']')) {
          index++;
          return list;
        }
        expect(',');
      }
    }

    private Object value() {
      skip();
      char c = text.charAt(index);
      if (c == '{') {
        return object();
      }
      if (c == '[') {
        return array();
      }
      if (c == '"') {
        return string();
      }
      int start = index;
      if (c == '-') {
        index++;
      }
      while (index < text.length() && Character.isDigit(text.charAt(index))) {
        index++;
      }
      return Long.valueOf(text.substring(start, index));
    }

    private String string() {
      expect('"');
      StringBuilder out = new StringBuilder();
      while (true) {
        char c = text.charAt(index++);
        if (c == '"') {
          return out.toString();
        }
        if (c == '\\') {
          out.append(text.charAt(index++));
        } else {
          out.append(c);
        }
      }
    }

    private void skip() {
      while (index < text.length() && text.charAt(index) <= ' ') {
        index++;
      }
    }

    private void expect(char c) {
      skip();
      if (text.charAt(index) != c) {
        throw new IllegalArgumentException("expected " + c);
      }
      index++;
    }

    private boolean peek(char c) {
      skip();
      return index < text.length() && text.charAt(index) == c;
    }
  }
}
