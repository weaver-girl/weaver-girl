# Compatibility matrix

> Status as of **2026-11-20**, agent `1.0.0-SNAPSHOT`. This page exists to make one distinction
> that the plugin list alone blurs: **what CI proves** versus **what is best-effort**.

Weaver-Girl ships 126 instrumentation plugins. Of those, the automated evidence is uneven, and
saying "supports Spring Boot 3.3" without a job behind it would be a claim the project cannot back.
So each row below states exactly what was executed and where, and everything not listed is marked
best-effort.

## How a plugin decides it applies

There is no per-library version metadata to derive a matrix from, and this page will not pretend
otherwise. A plugin declares one or more **fully-qualified class names** it instruments
(`WeaverPlugin.instrumentedLibrary()`), and the agent matches classes by name. Nothing in a plugin
records "tested against Spring 6.1.2" — a plugin POM depends only on `weaver-girl-api`, and its unit
tests assert the matcher strings, not a live framework.

Two consequences worth knowing before reading any row:

- **A framework upgrade can silently stop instrumentation** if a class name or method signature
  changes. That is why the last column matters more than the version column.
- **The plugin is inert when the library is absent.** Class-name matching means an unused plugin
  costs a class-name check and nothing else; a 126-plugin distribution does not need to be trimmed
  for correctness, only for startup time.

## JDK support — tested in CI

This is the only axis with exhaustive, per-version automation, and it is about the **agent
itself**, not any plugin.

| Agent JVM | How it is proven | Job |
|---|---|---|
| 8 | packaged agent attaches to a JDK-8 target; `/health`, `/ready`, `/stats`, `/metrics` asserted | `agent-smoke.yml` → `bare-attach` (matrix `8,11,17,21`) |
| 11 | same | `agent-smoke.yml` → `bare-attach` |
| 17 | same, plus the Jetty+H2 sample with the agent attached and interception asserted | `agent-smoke.yml` → `bare-attach`, `sample-attach` |
| 21 | same as 8/11 | `agent-smoke.yml` → `bare-attach` |
| Build JDK | 17 only; all main sources compile with `--release 8` | `ci.yml` → `build` (`17, 21`) |

`ci.yml`'s `compatibility-matrix` job also runs the packaged agent on 8/11/17/21, but it only
asserts the JVM starts — it is a smoke for "does `premain` crash", not for any plugin. The
`bare-attach` job is the stronger one.

The container image is `eclipse-temurin:8-jre`.

## Framework support — what each row actually proves

Legend:

- **CI-tested** — a job boots the framework with the agent attached *and* asserts interception.
- **Loaded in CI** — the framework is on the classpath of a CI boot with the agent attached, but no
  assertion is specific to this plugin.
- **Best-effort** — unit tests assert the matcher only; no automation has ever booted the target
  library under the agent.

| Plugin | Target class(es) it matches | Booted in CI | Level | Evidence |
|---|---|---|---|---|
| `servlet` | `javax.servlet.http.HttpServlet`, `jakarta.servlet.http.HttpServlet`, `*.Filter` | Jetty **9.4.58**, Servlet API **4.0.1** (javax) | **CI-tested** | `agent-smoke.yml` `sample-attach` asserts `weavergirl_operation_duration_ms_count{plugin="servlet"} ≥ 1` after driving `/users` and `/health` |
| `jetty` | `org.eclipse.jetty.server.HttpChannel` | Jetty **9.4.58** | Loaded in CI | same sample; the plugin activates but nothing asserts a jetty-specific series |
| `jdbc` | `java.sql.PreparedStatement` / `Statement` / `CallableStatement` | H2 **2.2.224** | Loaded in CI | SQL runs through H2 under the agent; only the servlet metric is asserted. `scripts/cross-jdk-smoke.sh` (not wired into CI) also checks that `topHooks` contains a `jdbc` hook |
| `hikari` | `com.zaxxer.hikari.pool.HikariPool`, `ProxyConnection` | — | Best-effort | matcher unit test only |
| `method-timing`, `timing`, `trace`, `trace-correlation`, `exception`, `logging` | framework-independent (any method / exception / HTTP headers) | Jetty sample | Loaded in CI | they fire on the sample, but no assertion is specific to them |

Everything below is **best-effort**: unit tests assert the matcher strings, and no automated job has
ever booted the target library under the agent.

| Plugin | Target class(es) it matches |
|---|---|
| `activej` | `io.activej.http.AsyncServlet` |
| `async` | `java.util.concurrent.{Callable,CompletableFuture,Executor,ExecutorService,ScheduledExecutorService}` |
| `asynchttp` | `org.asynchttpclient.AsyncHttpClient` |
| `avaje` | `io.avaje.jex.http.ExchangeHandler` |
| `awslambda` | `com.amazonaws.services.lambda.runtime.RequestHandler` |
| `awssdk` | `com.amazonaws.http.AmazonHttpClient` |
| `awssdk2` | `software.amazon.awssdk.awscore.client.handler.AwsAsyncClientHandler` |
| `azurecore` | `com.azure.core.http.HttpClient` |
| `c3p0` | `com.mchange.v2.c3p0.impl.AbstractPoolBackedDataSource` |
| `caffeine` | `com.github.benmanes.caffeine.cache.{Bounded,Unbounded}LocalCache` |
| `camel` | `org.apache.camel.processor.{SendProcessor,SendDynamicProcessor,MulticastProcessor,Enricher}` |
| `cassandra` | `com.datastax.oss.driver.api.core.cql.CqlSession` |
| `clickhouse` | `com.clickhouse.client.{ClickHouseClient,…}` |
| `commonshttp` | `org.apache.commons.httpclient.{HttpClient,HttpMethodDirector}` |
| `couchbase` | `com.couchbase.client.core.CouchbaseCore` |
| `cxf` | `org.apache.cxf.jaxrs.JAXRSInvoker` |
| `dbcp` | `org.apache.commons.dbcp2.BasicDataSource` |
| `dropwizard` | `io.dropwizard.views.ViewRenderer` |
| `druid` | `com.alibaba.druid.{filter.Filter,pool.DruidDataSource,…}` |
| `dubbo` | `org.apache.dubbo.rpc.{Filter,Invoker,proxy.AbstractProxyInvoker}` |
| `elasticapm` | `co.elastic.apm.api.{CaptureTransaction,Traced}` |
| `elasticjob` | `org.apache.shardingsphere.elasticjob.*.executor.*JobExecutor` |
| `elasticsearch` | `org.elasticsearch.client.{RestClient,RestHighLevelClient}`, `co.elastic.clients.elasticsearch.ElasticsearchClient` |
| `estransport` | `org.elasticsearch.client.support.AbstractClient` |
| `feign` | `feign.*` (matcher is built from `feign.Client`-style names; no literal to derive) |
| `finatra` | `com.twitter.finatra.http.internal.routing.Route` |
| `geode` | `org.apache.geode.cache.Region` |
| `ghttp`, `googlehttp` | `com.google.api.client.http.HttpRequest` |
| `grails` | `org.grails.web.mapping.mvc.UrlMappingsInfoHandlerAdapter` |
| `graphql` | `graphql.*` |
| `grizzly` | `org.glassfish.grizzly.http.server.HttpHandler` |
| `grpc` | `io.grpc.stub.{ClientCalls,ServerCalls$UnaryMethod}` |
| `gwt` | `com.google.gwt.user.server.rpc.RPC` |
| `hbase` | `org.apache.hadoop.hbase.ipc.AbstractRpcClient` |
| `hibernate` | `org.hibernate.{Query,Transaction}` |
| `hibernatesearch` | `org.hibernate.search.FullTextQuery`, `…engine.search.query.SearchQuery` |
| `httpasync` | `org.apache.http.nio.client.{HttpAsyncClient,HttpPipeliningClient}` |
| `httpclient` | `org.apache.http.impl.client.CloseableHttpClient` |
| `httpclient5` | `org.apache.hc.client5.http.{async,classic}.*` |
| `hystrix` | `com.netflix.hystrix.{HystrixCommand,HystrixObservableCommand}` |
| `influxdb` | `org.influxdb.impl.InfluxDBImpl` |
| `javalin` | `io.javalin.http.Handler` |
| `jaxrs` | `javax.ws.rs` / `jakarta.ws.rs` `ContainerRequestFilter` |
| `jaxws` | `javax.xml.ws.Provider`, `jakarta.xml.ws.Provider` |
| `jedis` | `redis.clients.jedis.Connection` |
| `jersey` | `org.glassfish.jersey.server.model.internal.AbstractJavaResourceMethodDispatcher` |
| `jetty12http` | `org.eclipse.jetty.client.transport.HttpRequest` |
| `jettyhttp` | `org.eclipse.jetty.client.HttpClient` |
| `jfinal` | `com.jfinal.aop.Invocation` |
| `jms` | `javax.jms` / `jakarta.jms` `{MessageProducer,MessageConsumer}` |
| `jodd` | `jodd.http.HttpRequest` |
| `jsf` | `javax.faces.event.ActionListener` and the MyFaces/Mojarra implementations |
| `jsp` | `javax.servlet.jsp.HttpJspPage`, `jakarta.servlet.jsp.HttpJspPage` |
| `kafka` | `org.apache.kafka.clients.{producer.KafkaProducer,consumer.KafkaConsumer}` |
| `kotlin` | `kotlinx.coroutines.flow.Flow` |
| `kubernetes` | `io.kubernetes.client.openapi.ApiClient` |
| `lettuce` | `io.lettuce.core.protocol.RedisChannelHandler` |
| `liberty` | `com.ibm.ws.webcontainer.webapp.WebApp` |
| `memcached` | `net.spy.memcached.MemcachedClient` |
| `mongo` | `com.mongodb.client.internal.{MongoClientImpl,MongoCollectionImpl,MongoDatabaseImpl}` |
| `mybatis` | `org.apache.ibatis.executor.{Executor,statement.StatementHandler}` |
| `nats` | `io.nats.client.{Connection,MessageHandler}` |
| `netty` | `io.netty.channel.AbstractChannelHandlerContext` |
| `ninghttp` | `com.ning.http.client.AsyncHttpClient` |
| `okhttp` | `okhttp3.internal.connection.{RealCall,RealConnectionPool}` |
| `opensearch` | `org.opensearch.client.RestClient` |
| `play` | `play.api.mvc.Action` |
| `playws` | `play.shaded.ahc.org.asynchttpclient.AsyncHttpClient` |
| `pool` | `org.apache.commons.pool2.impl.{GenericObjectPool,GenericKeyedObjectPool}` |
| `powerjob` | `tech.powerjob.worker.core.processor.sdk.BasicProcessor` |
| `pulsar` | `org.apache.pulsar.client.{api.MessageListener,impl.ProducerImpl}` |
| `quarkus` | `org.jboss.resteasy.reactive.common.core.AbstractResteasyReactiveContext` |
| `quartz` | `org.quartz.Job` |
| `rabbitmq` | `com.rabbitmq.client.{ConnectionFactory,impl.ChannelN,impl.WorkPool}` |
| `ratpack` | `ratpack.http.client.internal.RequestActionSupport` |
| `reactor` | `reactor.core.publisher.{Flux,Mono}` |
| `redis` | `redis.clients.jedis.{Jedis,JedisPool}`, `io.lettuce.core.*` |
| `rediscala` | `akka`/`redis` Scala client (matcher built at runtime) |
| `redisson` | `org.redisson.{api.RLock,command.RedisExecutor}` |
| `resteasy` | `org.jboss.resteasy.core.ResourceMethodInvoker` |
| `restlet` | `org.restlet.Server` |
| `resttemplate` | `org.springframework.http.client.ClientHttpRequest` |
| `rocketmq` | `org.apache.rocketmq.client.*` producer/consumer internals |
| `rxjava` | `io.reactivex.rxjava3.core.{Flowable,Observable}` |
| `scalaforkjoin` | `scala.concurrent.forkjoin.ForkJoinTask` |
| `scalafuture` | `scala.concurrent.impl.Promise`, `…Promise$Transformation` |
| `scheduled` | `org.springframework.scheduling.annotation.{Scheduled,Schedules}` |
| `scheduling` | `org.springframework.scheduling.support.DelegatingErrorHandlingRunnable` |
| `sparkjava` | `spark.route.Routes` |
| `spring` | `org.springframework.stereotype.{Service,Controller,Repository}` + mapping annotations |
| `springamqp` | `org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer` |
| `springbatch` | `org.springframework.batch.core.step.item.SimpleChunkProcessor` |
| `springjms` | `org.springframework.jms.listener.AbstractPollingMessageListenerContainer` |
| `springkafka` | `org.springframework.kafka.listener.KafkaMessageListenerContainer$ListenerConsumer` |
| `springmvc` | `org.springframework.web.servlet.HandlerAdapter` |
| `springpulsar` | `org.springframework.pulsar.listener.DefaultPulsarMessageListenerContainer` |
| `springrmi` | `org.springframework.remoting.rmi.{RmiBasedExporter,RmiClientInterceptor}` |
| `springws` | `org.springframework.ws.*` endpoint annotations |
| `struts`, `struts2` | `com.opensymphony.xwork2.ActionInvocation`, `org.apache.struts2.ActionInvocation` |
| `tapestry` | `org.apache.tapestry5.internal.structure.ComponentPageElementImpl` |
| `thrift` | `org.apache.thrift.{TBaseProcessor,TServiceClient}` |
| `traceanno` | `com.newrelic.api.agent.Trace`, `datadog.trace.api.Trace`, `com.appoptics.api.ext.LogMethod`, … |
| `traced`, `withspan` | `io.opentelemetry…annotations.WithSpan` |
| `twilio` | `com.twilio.base.{Creator,Deleter,Fetcher,Reader,Updater}` |
| `undertow` | `io.undertow.server.HttpHandler` |
| `vertx` | `io.vertx.ext.web.RoutingContext`, `io.vertx.core.http.HttpClientRequest` |
| `vertxredis` | `io.vertx.redis.client.impl.RedisStandaloneConnection` |
| `vertxsql` | `io.vertx.sqlclient.impl.QueryExecutor` |
| `vibur` | `org.vibur.dbcp.ViburDBCPDataSource` |
| `webflux` | `org.springframework.web.reactive.DispatcherHandler` |
| `websocket` | `javax.websocket.server.ServerEndpoint`, `jakarta.websocket.server.ServerEndpoint` |
| `wicket` | `org.apache.wicket.request.RequestHandlerExecutor` |
| `xxljob` | `com.xxl.job.core.handler.IJobHandler` |

The table groups plugins; the authoritative per-plugin list is
`weaver-girl-plugins/*/src/main/resources/META-INF/weavergirl-capabilities` and each plugin's
`instrumentedLibrary()`.

### The `logging` row

`logging` records interception events for log statements. Injecting the active `traceId`/`spanId`
into application log lines is handled separately: the engine's `MdcInjector` bridges ThreadContext
into SLF4J MDC on every intercepted call when SLF4J is present, so log lines emitted *inside* an
intercepted call already carry `traceId`/`spanId`/`tenantId`. That behaviour is unit-tested
(`MdcInjectorTest`) but not end-to-end asserted in CI.

## What the roadmap needs to move rows up

1. A matrix of **smoke applications, one per major row**, each booted with the agent and asserting a
   plugin-specific counter — the Jetty+H2 sample is the template.
2. A per-plugin way to state a tested version range, so this page can print it instead of `—`.
3. Wiring `scripts/cross-jdk-smoke.sh` and `scripts/verify-benchmarks.sh` into CI (both exist, both
   currently run only by hand).

Until then: **treat every Best-effort row as "the matcher was written against this class name", not
as a support statement.** Pin the framework version, and file an issue if interception does not
fire.
