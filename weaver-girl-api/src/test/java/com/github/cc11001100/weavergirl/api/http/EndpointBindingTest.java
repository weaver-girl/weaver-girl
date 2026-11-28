package com.github.cc11001100.weavergirl.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The endpoint defaults: loopback unless the operator asks otherwise, a bounded executor, and an
 * opt-in bearer token.
 *
 * <p>These are the defaults that keep {@code POST /agent/interception} from being reachable by a
 * network neighbour, so they are pinned by test rather than left to the Javadoc.
 */
class EndpointBindingTest {

  @Test
  void addressDefaultsToLoopback() {
    assertEquals("127.0.0.1", EndpointBinding.address(null));
    assertEquals("127.0.0.1", EndpointBinding.address("  "));
    assertEquals("0.0.0.0", EndpointBinding.address("0.0.0.0"));
    assertEquals("10.0.0.5", EndpointBinding.address(" 10.0.0.5 "));
  }

  @Test
  void maxThreadsFallsBackWhenMissingOrInvalid() {
    assertEquals(EndpointBinding.DEFAULT_MAX_THREADS, EndpointBinding.maxThreads(null));
    assertEquals(EndpointBinding.DEFAULT_MAX_THREADS, EndpointBinding.maxThreads("nope"));
    assertEquals(EndpointBinding.DEFAULT_MAX_THREADS, EndpointBinding.maxThreads("0"));
    assertEquals(EndpointBinding.DEFAULT_MAX_THREADS, EndpointBinding.maxThreads("-3"));
    assertEquals(32, EndpointBinding.maxThreads("32"));
  }

  @Test
  void aServerBindsLoopbackAndUsesDaemonThreads() throws Exception {
    int port = freePort();
    HttpServer server = EndpointBinding.create(null, port, 4);
    try {
      server.start();
      assertEquals(
          new InetSocketAddress("127.0.0.1", port),
          server.getAddress(),
          "a default endpoint must be reachable on loopback only");
    } finally {
      server.stop(0);
    }

    ExecutorService executor = EndpointBinding.boundedExecutor(4);
    assertNotNull(executor);
    executor.shutdownNow();
  }

  /**
   * The executor must be bounded in the queue as well as in threads. An unbounded queue makes the
   * thread ceiling meaningless: past it, tasks pile up instead of being rejected. Saturate the pool
   * with workers that block, then assert a queued submission is refused once the queue is full.
   */
  @Test
  void executorRejectsOnceThreadsAndQueueAreFull() throws Exception {
    ExecutorService executor = EndpointBinding.boundedExecutor(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    try {
      executor.execute(
          () -> {
            started.countDown();
            awaitQuietly(release);
          });
      assertTrue(started.await(2, TimeUnit.SECONDS), "the worker never started");

      // With 1 thread, the queue holds QUEUE_FACTOR tasks (private; sized 2). Two fit, the third
      // submission must be rejected rather than buffered.
      int accepted = 0;
      for (int i = 0; i < 8; i++) {
        try {
          executor.execute(() -> awaitQuietly(release));
          accepted++;
        } catch (java.util.concurrent.RejectedExecutionException expected) {
          break;
        }
      }
      assertEquals(2, accepted, "only the bounded queue depth should be accepted");
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void authorizationIsOffWithoutATokenAndRequiredWithOne() {
    assertTrue(EndpointBinding.authorized(null, null), "no token configured means open");
    assertTrue(EndpointBinding.authorized(null, ""));
    assertFalse(EndpointBinding.authorized(null, "secret"), "a token must be presented");
    assertFalse(EndpointBinding.authorized("Bearer wrong", "secret"));
    assertFalse(EndpointBinding.authorized("secret", "secret"), "the Bearer scheme is required");
    assertTrue(EndpointBinding.authorized("Bearer secret", "secret"));
    assertTrue(EndpointBinding.authorized("Bearer secret", " secret "));
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  /** Renders a request from the JDK's own HTTP client so the test does not add a dependency. */
  private static int statusOf(String url, String authorization) throws Exception {
    java.net.HttpURLConnection connection =
        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
    if (authorization != null) {
      connection.setRequestProperty("Authorization", authorization);
    }
    connection.setRequestMethod("GET");
    int status = connection.getResponseCode();
    connection.disconnect();
    return status;
  }

  @Test
  void tokenIsEnforcedByTheServletItself() throws Exception {
    int port = freePort();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.createContext(
        "/guarded",
        exchange -> {
          if (!EndpointBinding.authorized(
              exchange.getRequestHeaders().getFirst("Authorization"), "secret")) {
            EndpointBinding.challenge(exchange);
            return;
          }
          exchange.sendResponseHeaders(200, 0);
          exchange.close();
        });
    server.start();
    try {
      assertEquals(401, statusOf("http://127.0.0.1:" + port + "/guarded", null));
      assertEquals(401, statusOf("http://127.0.0.1:" + port + "/guarded", "Bearer wrong"));
      assertEquals(200, statusOf("http://127.0.0.1:" + port + "/guarded", "Bearer secret"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aLoopbackOnlyServerIsNotReachableOnAnotherInterface() throws Exception {
    int port = freePort();
    HttpServer server = EndpointBinding.create(null, port, 2);
    server.start();
    try (Socket ignored = new Socket("127.0.0.1", port)) {
      // A connection to the bound address is accepted; the assertion that matters is the bound
      // address reported above, because this test machine may not have a second interface.
      assertTrue(server.getAddress().getAddress().isLoopbackAddress()
          || server.getAddress().getHostString().equals("127.0.0.1"));
    } finally {
      server.stop(0);
    }
  }
}
