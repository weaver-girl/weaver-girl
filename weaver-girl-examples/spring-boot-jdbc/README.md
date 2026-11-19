# Spring Boot + JDBC with the released agent

A Spring Boot 3.3.5 application over H2, instrumented by the released Weaver-Girl agent distribution.
The application imports no weaver-girl class; the only difference between instrumented and not is
the JVM flags in `run.sh`.

## Run it

```bash
./run.sh                                     # downloads weaver-girl-agent-1.0.0-dist.zip
AGENT_ZIP=~/Downloads/weaver-girl-agent-1.0.0-dist.zip ./run.sh
AGENT_VERSION=1.0.0-rc.1 ./run.sh            # a different release
PORT=18080 ./run.sh                          # agent ports follow: 18081/18082/18083
```

`run.sh` builds the app, unpacks the distribution, attaches the agent and prints the URLs.

## Endpoints

| URL | What |
|---|---|
| `http://127.0.0.1:8080/orders` | `GET` — one JDBC query per call |
| `http://127.0.0.1:8080/orders` | `POST` `{"item":"bolt","cents":50}` — one insert |
| `http://127.0.0.1:8080/orders/1` | `GET` — single row |
| `http://127.0.0.1:8080/orders/slow` | `GET` — 1.5 s between two queries, trips the slow thresholds |
| `http://127.0.0.1:8081/metrics` | agent — Prometheus exposition |
| `http://127.0.0.1:8082/health`, `/ready` | agent — liveness / readiness |
| `http://127.0.0.1:8083/stats` | agent — JSON diagnostics, circuit breakers, top hooks |
| `http://127.0.0.1:8080/actuator/health` | Spring Boot's own actuator, untouched |

## Prove the interception

Startup success proves nothing — the agent can start and instrument nothing. Generate traffic, then
look for a non-zero counter:

```bash
curl -s localhost:8080/orders > /dev/null
curl -s localhost:8080/orders/slow > /dev/null
sleep 2
curl -s localhost:8081/metrics | grep -E 'plugin="(servlet|springmvc|jdbc)"'
```

```text
weavergirl_events_total{plugin="servlet",type="request"} 3
weavergirl_events_total{plugin="springmvc",type="slow-springmvc"} 1
weavergirl_operation_duration_ms_count{plugin="servlet"} 3
weavergirl_operation_duration_ms_count{plugin="springmvc"} 1
weavergirl_interceptor_invocations_total{interceptor="jdbc-java.sql.Statement-execute"} 12
```

Interception latency per hook, plugin health and circuit-breaker state are on the same endpoint; the
dashboards in [`deploy/grafana/`](../../deploy/grafana/) read them.

## Running it without the agent

```bash
java -jar target/weaver-girl-example-spring-boot-jdbc-1.0.0.jar
```

The application works identically. That is the intended contract: instrumentation is an observer,
not a dependency.

## Build only

```bash
../../mvnw -f pom.xml package
```

No reactor build, no local install, no weaver-girl artifact resolved. If Maven needs to reach Central
for Spring Boot, it does — the same as any other Spring Boot project.
