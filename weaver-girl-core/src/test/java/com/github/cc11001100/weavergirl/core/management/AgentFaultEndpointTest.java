package com.github.cc11001100.weavergirl.core.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** GET /diagnostics/faults reads the shipped recorder. */
class AgentFaultEndpointTest {

  private AgentApiServer server;
  private int port;

  @BeforeEach
  void setUp() throws Exception {
    AgentDiagnostics.getInstance().clearFaults();
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
    AgentDiagnostics.getInstance().clearFaults();
  }

  @Test
  void faultsEndpointListsRecordedFaults() throws Exception {
    String empty = get("/diagnostics/faults");
    assertEquals(200, lastStatus);
    assertTrue(empty.contains("\"faults\":[]"));
    assertTrue(empty.contains("\"count\":0"));
    System.out.println("FAULT-HTTP-EMPTY " + empty);

    AgentDiagnostics.getInstance().recordFault("ADVICE_BRIDGE", "advice bridge class missing");
    String one = get("/diagnostics/faults");
    assertTrue(one.contains("\"type\":\"ADVICE_BRIDGE\""));
    assertTrue(one.contains("\"message\":\"advice bridge class missing\""));
    assertTrue(one.contains("\"count\":1"));
    System.out.println("FAULT-HTTP-1 " + one);

    AgentDiagnostics.getInstance().recordFault("RETRANSFORM", "retransform rejected");
    String two = get("/diagnostics/faults");
    String again = get("/diagnostics/faults");
    assertEquals(two, again);
    assertTrue(two.contains("\"type\":\"ADVICE_BRIDGE\""));
    assertTrue(two.contains("\"message\":\"advice bridge class missing\""));
    assertTrue(two.contains("\"type\":\"RETRANSFORM\""));
    assertTrue(two.contains("\"message\":\"retransform rejected\""));
    assertTrue(two.contains("\"count\":2"));
    System.out.println("FAULT-HTTP-2 " + two);
    System.out.println("FAULT-HTTP-2-AGAIN " + again);
  }

  private int lastStatus;

  private String get(String path) throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
    connection.setRequestMethod("GET");
    lastStatus = connection.getResponseCode();
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
}
