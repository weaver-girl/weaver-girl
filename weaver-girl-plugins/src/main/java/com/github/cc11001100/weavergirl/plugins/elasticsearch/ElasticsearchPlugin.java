package com.github.cc11001100.weavergirl.plugins.elasticsearch;

import com.github.cc11001100.weavergirl.api.context.ThreadContext;
import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Elasticsearch client instrumentation plugin. Intercepts ES REST and Transport client operations
 * to:
 *
 * <ul>
 *   <li>Track search, index, bulk, delete operation timing
 *   <li>Extract index name and operation type
 *   <li>Detect slow queries
 *   <li>Track bulk operation sizes
 *   <li>Propagate trace context via headers
 * </ul>
 *
 * <p>Target classes (string names, no compile dependency):
 *
 * <ul>
 *   <li>{@code org.elasticsearch.client.RestHighLevelClient} &mdash; search, index, bulk, delete,
 *       update, get
 *   <li>{@code org.elasticsearch.client.RestClient} &mdash; low-level performRequest
 *   <li>{@code org.elasticsearch.transport.TransportService} &mdash; transport layer (for transport
 *       client)
 * </ul>
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code slowQueryThreshold} &mdash; Slow query threshold in ms (default: 3000)
 *   <li>{@code trackBulkSize} &mdash; Track bulk operation item count (default: true)
 *   <li>{@code propagateTrace} &mdash; Propagate trace headers (default: true)
 *   <li>{@code enabled} &mdash; Enable/disable (default: true)
 * </ul>
 */
public class ElasticsearchPlugin extends AbstractPlugin {

  private static final Logger log = LoggerFactory.getLogger(ElasticsearchPlugin.class);

  private long slowQueryThresholdMs = 3000;
  private boolean trackBulkSize = true;
  private boolean propagateTrace = true;
  private boolean enabled = true;

  // Target class names
  private static final String REST_HIGH_LEVEL_CLIENT =
      "org.elasticsearch.client.RestHighLevelClient";
  private static final String REST_CLIENT = "org.elasticsearch.client.RestClient";
  private static final String JAVA_CLIENT = "co.elastic.clients.elasticsearch.ElasticsearchClient";

  @Override
  public String name() {
    return "elasticsearch";
  }

  @Override
  public void init(PluginContext context) {
    slowQueryThresholdMs = context.getConfigLong("slowQueryThreshold", 3000);
    trackBulkSize = context.getConfigBoolean("trackBulkSize", true);
    propagateTrace = context.getConfigBoolean("propagateTrace", true);
    enabled = context.getConfigBoolean("enabled", true);
  }

  @Override
  public void registerInterceptors(
      com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry registry) {
    if (!enabled) return;

    // --- High-level REST client interceptor ---
    Interceptor highLevelInterceptor =
        new Interceptor() {
          private final ThreadLocal<Long> startTime = new ThreadLocal<>();

          @Override
          public void before(MethodInvocation inv) {
            startTime.set(System.nanoTime());

            if (propagateTrace) {
              injectTraceHeaders(inv);
            }

            if (log.isDebugEnabled()) {
              log.debug(
                  "[ES-HIGH] {}.{} started",
                  inv.getTargetClass().getSimpleName(),
                  inv.getMethodName());
            }
          }

          @Override
          public void after(MethodInvocation inv) {
            Long start = startTime.get();
            startTime.remove();
            if (start == null) return;

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            String operation = inv.getMethodName();
            String indexName = extractIndexName(inv);
            int hitCount = extractHitCount(inv);
            int tookMs = extractTookMs(inv);
            int bulkSize = trackBulkSize && "bulk".equals(operation) ? extractBulkSize(inv) : -1;

            if (elapsedMs >= slowQueryThresholdMs) {
              log.warn(
                  "[SLOW-ES] {} on index '{}' took {}ms (threshold: {}ms)",
                  operation,
                  indexName != null ? indexName : "*",
                  elapsedMs,
                  slowQueryThresholdMs);
            } else if (log.isDebugEnabled()) {
              log.debug(
                  "[ES-HIGH] {} on index '{}' took {}ms",
                  operation,
                  indexName != null ? indexName : "*",
                  elapsedMs);
            }

            InterceptorEvent.Builder eventBuilder =
                InterceptorEvent.builder()
                    .type(
                        elapsedMs >= slowQueryThresholdMs ? "slow-elasticsearch" : "elasticsearch")
                    .plugin("elasticsearch")
                    .className(inv.getTargetClass().getSimpleName())
                    .methodName(operation)
                    .durationMs(elapsedMs);
            if (indexName != null) eventBuilder.attribute("index", indexName);
            eventBuilder.attribute("operation", operation);
            if (hitCount >= 0) eventBuilder.attribute("hitCount", String.valueOf(hitCount));
            if (tookMs >= 0) eventBuilder.attribute("serverTookMs", String.valueOf(tookMs));
            if (bulkSize >= 0) eventBuilder.attribute("bulkSize", String.valueOf(bulkSize));

            InterceptorEventPublisher.getInstance().publish(eventBuilder.build());
          }

          @Override
          public void onException(MethodInvocation inv) {
            startTime.remove();
            String error = inv.getThrowable() != null ? inv.getThrowable().getMessage() : "unknown";
            log.warn(
                "[ES-ERROR] {}.{}: {}",
                inv.getTargetClass().getSimpleName(),
                inv.getMethodName(),
                error);
            InterceptorEventPublisher.getInstance()
                .publish(
                    InterceptorEvent.builder()
                        .type("elasticsearch-error")
                        .plugin("elasticsearch")
                        .className(inv.getTargetClass().getSimpleName())
                        .methodName(inv.getMethodName())
                        .attribute("error", error)
                        .build());
          }
        };

    // --- Low-level REST client interceptor ---
    Interceptor lowLevelInterceptor =
        new Interceptor() {
          private final ThreadLocal<Long> startTime = new ThreadLocal<>();

          @Override
          public void before(MethodInvocation inv) {
            startTime.set(System.nanoTime());
            if (log.isDebugEnabled()) {
              String endpoint = extractEndpoint(inv);
              log.debug("[ES-LOW] {} started", endpoint != null ? endpoint : inv.getMethodName());
            }
          }

          @Override
          public void after(MethodInvocation inv) {
            Long start = startTime.get();
            startTime.remove();
            if (start == null) return;

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            String endpoint = extractEndpoint(inv);
            int statusCode = extractStatusCode(inv);

            if (elapsedMs >= slowQueryThresholdMs) {
              log.warn("[SLOW-ES-LOW] {} took {}ms, status={}", endpoint, elapsedMs, statusCode);
            }

            InterceptorEvent.Builder eventBuilder =
                InterceptorEvent.builder()
                    .type(
                        elapsedMs >= slowQueryThresholdMs
                            ? "slow-elasticsearch-rest"
                            : "elasticsearch-rest")
                    .plugin("elasticsearch")
                    .className(inv.getTargetClass().getSimpleName())
                    .methodName(inv.getMethodName())
                    .durationMs(elapsedMs);
            if (endpoint != null) eventBuilder.attribute("endpoint", endpoint);
            if (statusCode > 0) eventBuilder.attribute("statusCode", String.valueOf(statusCode));

            InterceptorEventPublisher.getInstance().publish(eventBuilder.build());
          }

          @Override
          public void onException(MethodInvocation inv) {
            startTime.remove();
            String error = inv.getThrowable() != null ? inv.getThrowable().getMessage() : "unknown";
            log.warn(
                "[ES-LOW-ERROR] {}.{}: {}",
                inv.getTargetClass().getSimpleName(),
                inv.getMethodName(),
                error);
            InterceptorEventPublisher.getInstance()
                .publish(
                    InterceptorEvent.builder()
                        .type("elasticsearch-rest-error")
                        .plugin("elasticsearch")
                        .className(inv.getTargetClass().getSimpleName())
                        .methodName(inv.getMethodName())
                        .attribute("error", error)
                        .build());
          }
        };

    // Register RestHighLevelClient interceptor
    registry.register(
        interceptSubclassOf(REST_HIGH_LEVEL_CLIENT)
            .methodPattern(
                "search|index|bulk|delete|update|get|msearch|scroll|clearScroll|reindex|count|exists")
            .around(inv -> highLevelInterceptor.before(inv), inv -> highLevelInterceptor.after(inv))
            .priority(10)
            .build());

    // Register new ES Java client (8.x+)
    registry.register(
        interceptClassPattern(JAVA_CLIENT.replace(".", "\\."))
            .methodPattern(
                "search|index|bulk|delete|update|get|msearch|scroll|reindex|count|exists")
            .around(inv -> highLevelInterceptor.before(inv), inv -> highLevelInterceptor.after(inv))
            .priority(10)
            .build());

    // Register low-level RestClient interceptor
    registry.register(
        interceptSubclassOf(REST_CLIENT)
            .methodPattern("performRequest|performRequestAsync")
            .around(inv -> lowLevelInterceptor.before(inv), inv -> lowLevelInterceptor.after(inv))
            .priority(15) // Lower priority than high-level (runs outer)
            .build());
  }

  /**
   * Extract index name from the request argument via reflection. SearchRequest.getIndex(),
   * IndexRequest.index(), etc.
   */
  String extractIndexName(MethodInvocation inv) {
    try {
      if (inv.getArguments() != null && inv.getArguments().length > 0) {
        Object arg = inv.getArgument(0);
        if (arg == null) return null;

        // Try getIndex() / index()
        try {
          java.lang.reflect.Method indexMethod = arg.getClass().getMethod("getIndex");
          Object index = indexMethod.invoke(arg);
          if (index != null) return index.toString();
        } catch (NoSuchMethodException e) {
          try {
            java.lang.reflect.Method indexMethod = arg.getClass().getMethod("index");
            Object index = indexMethod.invoke(arg);
            if (index != null) return index.toString();
          } catch (NoSuchMethodException e2) {
            // Try indices() for multi-index requests
            try {
              java.lang.reflect.Method indicesMethod = arg.getClass().getMethod("indices");
              Object indices = indicesMethod.invoke(arg);
              if (indices instanceof String[]) {
                String[] arr = (String[]) indices;
                if (arr.length > 0) return String.join(",", arr);
              }
            } catch (NoSuchMethodException e3) {
              // Fall through
            }
          }
        }
      }
    } catch (Exception e) {
      // Reflection failed — ignore
    }
    return null;
  }

  /** Extract hit count from search response via reflection. */
  int extractHitCount(MethodInvocation inv) {
    try {
      Object returnValue = inv.getReturnValue();
      if (returnValue == null) return -1;

      // Try getHits().getTotalHits().value (ES 7.x+)
      try {
        java.lang.reflect.Method getHits = returnValue.getClass().getMethod("getHits");
        Object hits = getHits.invoke(returnValue);
        if (hits != null) {
          try {
            java.lang.reflect.Method getTotalHits = hits.getClass().getMethod("getTotalHits");
            Object totalHits = getTotalHits.invoke(hits);
            if (totalHits != null) {
              java.lang.reflect.Method value = totalHits.getClass().getMethod("value");
              Object val = value.invoke(totalHits);
              if (val instanceof Long) return ((Long) val).intValue();
              if (val instanceof Integer) return (Integer) val;
            }
          } catch (NoSuchMethodException e) {
            // ES 6.x: getHits().getTotalHits() returns long
            try {
              java.lang.reflect.Method getTotalHits = hits.getClass().getMethod("getTotalHits");
              Object totalHits = getTotalHits.invoke(hits);
              if (totalHits instanceof Long) return ((Long) totalHits).intValue();
              if (totalHits instanceof Integer) return (Integer) totalHits;
            } catch (NoSuchMethodException e2) {
              // Fall through
            }
          }
        }
      } catch (NoSuchMethodException e) {
        // Fall through
      }

      // Try ES 8.x Java client: hits().total().value()
      try {
        java.lang.reflect.Method hitsMethod = returnValue.getClass().getMethod("hits");
        Object hits = hitsMethod.invoke(returnValue);
        if (hits != null) {
          java.lang.reflect.Method totalMethod = hits.getClass().getMethod("total");
          Object total = totalMethod.invoke(hits);
          if (total != null) {
            java.lang.reflect.Method valueMethod = total.getClass().getMethod("value");
            Object val = valueMethod.invoke(total);
            if (val instanceof Long) return ((Long) val).intValue();
          }
        }
      } catch (Exception e) {
        // Fall through
      }
    } catch (Exception e) {
      // Reflection failed — ignore
    }
    return -1;
  }

  /** Extract server-side took time from response via reflection. */
  int extractTookMs(MethodInvocation inv) {
    try {
      Object returnValue = inv.getReturnValue();
      if (returnValue == null) return -1;

      java.lang.reflect.Method tookMethod = returnValue.getClass().getMethod("took");
      Object took = tookMethod.invoke(returnValue);
      if (took != null) {
        if (took instanceof Long) return ((Long) took).intValue();
        if (took instanceof Integer) return (Integer) took;
        // TimeValue wrapper
        try {
          java.lang.reflect.Method millisMethod = took.getClass().getMethod("millis");
          Object millis = millisMethod.invoke(took);
          if (millis instanceof Long) return ((Long) millis).intValue();
        } catch (NoSuchMethodException e) {
          // Fall through
        }
      }
    } catch (Exception e) {
      // Reflection failed — ignore
    }
    return -1;
  }

  /** Extract bulk request item count via reflection. */
  int extractBulkSize(MethodInvocation inv) {
    try {
      if (inv.getArguments() != null && inv.getArguments().length > 0) {
        Object arg = inv.getArgument(0);
        if (arg != null) {
          try {
            java.lang.reflect.Method sizeMethod = arg.getClass().getMethod("numberOfActions");
            Object size = sizeMethod.invoke(arg);
            if (size instanceof Integer) return (Integer) size;
          } catch (NoSuchMethodException e) {
            try {
              java.lang.reflect.Method sizeMethod = arg.getClass().getMethod("size");
              Object size = sizeMethod.invoke(arg);
              if (size instanceof Integer) return (Integer) size;
            } catch (NoSuchMethodException e2) {
              // Fall through
            }
          }
        }
      }
    } catch (Exception e) {
      // Reflection failed — ignore
    }
    return -1;
  }

  /** Extract endpoint from low-level REST request via reflection. */
  String extractEndpoint(MethodInvocation inv) {
    try {
      if (inv.getArguments() != null && inv.getArguments().length > 0) {
        Object arg = inv.getArgument(0);
        if (arg != null) {
          // Try getEndpoint() or endpoint()
          try {
            java.lang.reflect.Method endpointMethod = arg.getClass().getMethod("getEndpoint");
            Object endpoint = endpointMethod.invoke(arg);
            if (endpoint != null) return endpoint.toString();
          } catch (NoSuchMethodException e) {
            try {
              java.lang.reflect.Method endpointMethod = arg.getClass().getMethod("endpoint");
              Object endpoint = endpointMethod.invoke(arg);
              if (endpoint != null) return endpoint.toString();
            } catch (NoSuchMethodException e2) {
              // Try toString as fallback
              String str = arg.toString();
              if (str != null && !str.isEmpty()) return str;
            }
          }
        }
      }
    } catch (Exception e) {
      // Reflection failed — ignore
    }
    return null;
  }

  /** Extract HTTP status code from low-level response via reflection. */
  int extractStatusCode(MethodInvocation inv) {
    try {
      Object returnValue = inv.getReturnValue();
      if (returnValue != null) {
        try {
          java.lang.reflect.Method statusMethod = returnValue.getClass().getMethod("getStatusLine");
          Object statusLine = statusMethod.invoke(returnValue);
          if (statusLine != null) {
            java.lang.reflect.Method codeMethod = statusLine.getClass().getMethod("getStatusCode");
            Object code = codeMethod.invoke(statusLine);
            if (code instanceof Integer) return (Integer) code;
          }
        } catch (NoSuchMethodException e) {
          // ES 8.x: Response.getStatus() or status()
          try {
            java.lang.reflect.Method statusMethod = returnValue.getClass().getMethod("status");
            Object status = statusMethod.invoke(returnValue);
            if (status != null) {
              // Try statusCode()
              try {
                java.lang.reflect.Method codeMethod = status.getClass().getMethod("statusCode");
                Object code = codeMethod.invoke(status);
                if (code instanceof Integer) return (Integer) code;
              } catch (NoSuchMethodException e2) {
                return status.hashCode();
              }
            }
          } catch (NoSuchMethodException e2) {
            // Fall through
          }
        }
      }
    } catch (Exception e) {
      // Reflection failed — ignore
    }
    return 0;
  }

  /** Inject trace headers into ES request via reflection. */
  void injectTraceHeaders(MethodInvocation inv) {
    try {
      String traceId = ThreadContext.get("traceId");
      if (traceId == null) return;

      // For high-level client: setHeader on the request object
      if (inv.getArguments() != null && inv.getArguments().length > 0) {
        Object arg = inv.getArgument(0);
        if (arg != null) {
          try {
            java.lang.reflect.Method setHeader =
                arg.getClass().getMethod("setHeader", String.class, String.class);
            setHeader.invoke(arg, "X-Trace-Id", traceId);
            String spanId = ThreadContext.get("spanId");
            if (spanId != null) {
              setHeader.invoke(arg, "X-Span-Id", spanId);
            }
          } catch (NoSuchMethodException e) {
            // Try putHeader() for some request types
            try {
              java.lang.reflect.Method putHeader =
                  arg.getClass().getMethod("putHeader", String.class, String.class);
              putHeader.invoke(arg, "X-Trace-Id", traceId);
            } catch (NoSuchMethodException e2) {
              // Cannot inject — non-critical
            }
          }
        }
      }
    } catch (Exception e) {
      if (log.isDebugEnabled()) {
        log.debug("[ES] Failed to inject trace: {}", e.getMessage());
      }
    }
  }

  // Expose for testing
  long getSlowQueryThresholdMs() {
    return slowQueryThresholdMs;
  }

  boolean isTrackBulkSize() {
    return trackBulkSize;
  }

  boolean isPropagateTrace() {
    return propagateTrace;
  }

  boolean isEnabled() {
    return enabled;
  }
}
