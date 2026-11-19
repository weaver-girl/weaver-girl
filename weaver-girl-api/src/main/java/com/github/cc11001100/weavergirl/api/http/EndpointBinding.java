package com.github.cc11001100.weavergirl.api.http;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How the agent's HTTP endpoints bind, and what executor serves them.
 *
 * <p>The default is loopback only: {@code /events}, {@code /traces}, {@code /audit} and the
 * global interception switch ({@code POST /agent/interception}) used to be reachable from anywhere
 * that could route to the port, which let any network neighbour turn instrumentation off. An
 * operator who needs remote access asks for it explicitly with {@code endpointBindAddress}.
 *
 * <p>The executor is bounded. The JDK default ({@code null}) is one thread per request with no
 * limit; a scrape loop or a port scan could spawn threads until the JVM ran out of memory.
 *
 * <h3>Configuration</h3>
 *
 * <ul>
 *   <li>{@code endpointBindAddress} — the address to bind. Defaults to {@code 127.0.0.1}. Set it to
 *       {@code 0.0.0.0} to expose the endpoint to the network (and then put it behind a firewall or
 *       a proxy, because the endpoints have no authentication).
 *   <li>{@code endpointMaxThreads} — the executor's thread ceiling. Defaults to 16.
 * </ul>
 *
 * @since 1.0.1
 */
public final class EndpointBinding {

  /** Loopback by default: see the class comment for why. */
  public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";

  /** Thread ceiling for one endpoint server. */
  public static final int DEFAULT_MAX_THREADS = 16;

  private EndpointBinding() {}

  /**
   * Bind address for an endpoint, from the agent configuration.
   *
   * @param configurationValue the configured value, or null
   * @return the configured address, or the loopback default
   */
  public static String address(String configurationValue) {
    if (configurationValue == null || configurationValue.trim().isEmpty()) {
      return DEFAULT_BIND_ADDRESS;
    }
    return configurationValue.trim();
  }

  /**
   * Thread ceiling for an endpoint, from the agent configuration.
   *
   * @param configurationValue the configured value, or null
   * @return the configured ceiling, or the default; values below 1 fall back to the default
   */
  public static int maxThreads(String configurationValue) {
    if (configurationValue == null) {
      return DEFAULT_MAX_THREADS;
    }
    try {
      int parsed = Integer.parseInt(configurationValue.trim());
      return parsed > 0 ? parsed : DEFAULT_MAX_THREADS;
    } catch (NumberFormatException notANumber) {
      return DEFAULT_MAX_THREADS;
    }
  }

  /**
   * Create a server bound to {@code address:port}.
   *
   * @param port the port
   * @param address the bind address, or null for the loopback default
   * @param maxThreads the executor ceiling, or a value below 1 for the default
   * @return a started server with a bounded executor
   * @throws IOException when the address cannot be bound
   */
  public static HttpServer create(String address, int port, int maxThreads) throws IOException {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(address(address), port), 0);
    server.setExecutor(boundedExecutor(maxThreads));
    return server;
  }

  /**
   * A bounded executor for one endpoint server.
   *
   * <p>Daemon threads: an idle endpoint must never keep the application's JVM from exiting.
   *
   * @param maxThreads the thread ceiling
   * @return the executor
   */
  public static ExecutorService boundedExecutor(int maxThreads) {
    final AtomicInteger counter = new AtomicInteger();
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            maxThreads <= 0 ? DEFAULT_MAX_THREADS : maxThreads,
            maxThreads <= 0 ? DEFAULT_MAX_THREADS : maxThreads,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            new java.util.concurrent.ThreadFactory() {
              @Override
              public Thread newThread(Runnable runnable) {
                Thread thread =
                    new Thread(runnable, "weaver-girl-endpoint-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
              }
            });
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  /**
   * Refuse a request whose {@code Authorization} header does not carry the configured token.
   *
   * <p>Empty token means "no authentication", which is the default for a loopback-bound endpoint.
   *
   * @param authorizationHeader the header as received, or null
   * @param token the configured token, or null/empty when authentication is off
   * @return true when the request may proceed
   */
  public static boolean authorized(String authorizationHeader, String token) {
    if (token == null || token.trim().isEmpty()) {
      return true;
    }
    return ("Bearer " + token.trim()).equals(authorizationHeader);
  }

  /**
   * Answer a request with the standard "authentication required" response.
   *
   * @param exchange the request
   * @throws IOException when the response cannot be written
   */
  public static void challenge(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    exchange
        .getResponseHeaders()
        .set("WWW-Authenticate", "Bearer realm=\"weaver-girl\"");
    byte[] body = "{\"error\":\"authentication required\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(401, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
