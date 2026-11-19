# Grafana dashboard pack

Three dashboards for the metrics the agent actually exports on `metricsPort` (default `9400`):

| File | UID | Covers |
|---|---|---|
| `weaver-girl-operations.json` | `weaver-girl-operations` | request/query rate, slow-call ratio, error ratio, p95/p99 interception latency, plugin health, plugin lifecycle state, **circuit-breaker state** |
| `weaver-girl-interceptors.json` | `weaver-girl-interceptors` | per-interceptor invocation rate, latency quantiles, slow calls, top-10 slowest hooks |
| `weaver-girl-jvm.json` | `weaver-girl-jvm` | heap / non-heap memory, threads, GC time and count, classes, uptime |

Every panel query in these files names a series the exporter emits; the mapping is documented in
[`docs/observability.md`](../../docs/observability.md) §4. A panel that returns "No data" almost
always means the plugin that produces that series is not enabled — e.g. `weavergirl_plugin_state`
only exists when a `PluginManager` is installed, and `weavergirl_circuit_breaker_*` only after a
hook has been observed at least once.

## Prerequisites

1. Prometheus scraping the agent endpoint. It binds `127.0.0.1` by default, so a Prometheus on
   another host needs a sidecar or reverse proxy, or `endpointBindAddress=0.0.0.0` plus a network
   policy. Example scrape config:

   ```yaml
   scrape_configs:
     - job_name: weaver-girl
       static_configs:
         - targets: ["10.0.0.12:9400"]
   ```

2. Grafana 10+. Each dashboard has a **Datasource** variable at the top; pick the Prometheus that
   scrapes the agent, and the panels follow it.

## Import

**UI:** Dashboards → New → Import → *Upload JSON file*, repeat per file. The `uid` is fixed, so
re-importing updates the existing dashboard instead of creating a duplicate.

**CLI:**

```bash
./load-dashboards.sh http://localhost:3000 admin:admin
```

The script wraps each file in Grafana's import payload and POSTs it to `/api/dashboards/db`;
`overwrite: true` makes it idempotent. Point it at an API key instead of `admin:admin` for a
non-local instance.

## Variables

| Variable | Type | Values |
|---|---|---|
| `datasource` | datasource | the Prometheus data source to query |
| `plugin` (operations only) | query, multi | `label_values(weavergirl_events_total, plugin)`, All = `.*` |
| `interceptor` (operations only) | query, multi | `label_values(weavergirl_interceptor_invocations_total, interceptor)`, All = `.*` |

## Alerts worth wiring

| Expression | Means |
|---|---|
| `weavergirl_plugin_health{status!="healthy"} == 1` | a plugin reported degraded or unhealthy |
| `weavergirl_plugin_state{state!="active"} == 1` | a plugin was disabled by config or failed to initialise |
| `weavergirl_circuit_breaker_open == 1` | a hook tripped its breaker and is **not running** until the cooldown expires |
| `weavergirl_extension_error_rate > 0.1` | an extension point (span sink, processor, …) is throwing on most calls |
| `weavergirl_spans_drop_ratio > 0.05` | the trace export pipeline is dropping spans |

## What is *not* here

The exporter has no daemon-thread gauge and no GC-pause histogram, only the cumulative
`weavergirl_jvm_gc_collection_{count,time}_seconds_total` counters — so the JVM dashboard derives
rates from those instead of querying Micrometer-style `jvm_gc_pause_seconds_*`, which the agent
never emits.
