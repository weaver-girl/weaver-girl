/** Static site content. Mirrors README.md, docs/ARCHITECTURE.md and the module tree. */

export const REPO_URL = "https://github.com/weaver-girl/weaver-girl";
export const SITE_URL = "https://weaver-girl.github.io/weaver-girl/";

export interface Feature {
  title: string;
  description: string;
}

export const features: Feature[] = [
  {
    title: "126 个内置插件",
    description:
      "Servlet、Spring、JDBC、MyBatis、Redis、Kafka、RocketMQ、gRPC、Dubbo、Feign、Netty、Tomcat 等开箱即用。每个能力一个 jar，目标库不在场就不会加载。",
  },
  {
    title: "三种接入写法",
    description:
      "编程式 API、声明式注解、YAML 配置，共用同一套拦截器注册表。业务代码一行不改，也能精确控制拦截范围。",
  },
  {
    title: "插件即产品底座",
    description:
      "做 APM、IAST 或 RASP 只需写一个插件：方法、字段、异常捕获三种拦截，加上追踪、指标、导出、拓扑、告警和污点分析，全部只依赖 weaver-girl-api。",
  },
  {
    title: "零分配字节码织入",
    description:
      "基于 ByteBuddy Advice 内联拦截，支持跳过原方法与改写返回值，热路径上不产生额外对象；必要时可注入 bootstrap，连 java.* 也能拦。",
  },
  {
    title: "动态挂载与热加载",
    description:
      "agentmain 运行时挂载并重转换已加载类；YAML 变更无需重启即可生效，关闭或开启插件同样即时。",
  },
  {
    title: "生产级自我保护",
    description:
      "熔断器自动摘除异常拦截器，自适应采样在高负载下降开销，插件类加载器隔离，安全策略与审计日志托底。",
  },
];

export interface PluginRow {
  key: string;
  name: string;
  target: string;
  config: string;
}

/** Most-used plugins. The full 126-name list is rendered from `allPlugins` below. */
export const pluginHighlights: PluginRow[] = [
  { key: "servlet", name: "servlet", target: "javax.servlet", config: "slowRequestThreshold" },
  { key: "spring", name: "spring", target: "Spring Framework", config: "slowThreshold" },
  { key: "springmvc", name: "springmvc", target: "Spring MVC", config: "slowThreshold" },
  { key: "jdbc", name: "jdbc", target: "java.sql", config: "slowQueryThreshold, logSql" },
  { key: "mybatis", name: "mybatis", target: "MyBatis", config: "slowQueryThreshold" },
  { key: "hibernate", name: "hibernate", target: "Hibernate", config: "slowQueryThreshold" },
  { key: "druid", name: "druid", target: "Druid", config: "slowQueryThreshold" },
  { key: "hikari", name: "hikari", target: "HikariCP", config: "leakThresholdMs, trackAcquisition" },
  { key: "redis", name: "redis", target: "Jedis / Lettuce", config: "slowCommandThreshold" },
  { key: "redisson", name: "redisson", target: "Redisson", config: "slowCommandThreshold" },
  { key: "caffeine", name: "caffeine", target: "Caffeine Cache", config: "cacheOperationThreshold" },
  { key: "kafka", name: "kafka", target: "Apache Kafka", config: "slowThreshold" },
  { key: "rocketmq", name: "rocketmq", target: "Apache RocketMQ", config: "slowThreshold" },
  { key: "rabbitmq", name: "rabbitmq", target: "RabbitMQ Client", config: "slowPublishThreshold" },
  { key: "pulsar", name: "pulsar", target: "Apache Pulsar", config: "slowThreshold" },
  { key: "grpc", name: "grpc", target: "io.grpc", config: "slowThreshold" },
  { key: "dubbo", name: "dubbo", target: "Apache Dubbo", config: "slowThreshold" },
  { key: "feign", name: "feign", target: "OpenFeign", config: "slowThreshold" },
  { key: "httpclient", name: "httpclient", target: "Apache HttpClient", config: "slowThreshold" },
  { key: "okhttp", name: "okhttp", target: "OkHttp 3.x / 4.x", config: "slowThreshold, trackConnectionPool" },
  { key: "netty", name: "netty", target: "Netty pipeline", config: "slowThreshold" },
  { key: "tomcat", name: "tomcat", target: "Tomcat connector", config: "slowThreshold" },
  { key: "jetty", name: "jetty", target: "Jetty handler", config: "slowThreshold" },
  { key: "undertow", name: "undertow", target: "Undertow handler", config: "slowThreshold" },
  { key: "quartz", name: "quartz", target: "Quartz Scheduler", config: "slowJobThreshold" },
  { key: "xxljob", name: "xxljob", target: "XXL-Job executor", config: "slowJobThreshold" },
  { key: "timing", name: "timing", target: "任意方法", config: "slowThreshold" },
  { key: "exception", name: "exception", target: "任意异常", config: "maxStackTraceLength" },
  { key: "logging", name: "logging", target: "SLF4J / Log4j2", config: "logLevel" },
  { key: "trace", name: "trace", target: "W3C Trace Context", config: "headerName" },
];

/**
 * Every instrumentation plugin module in weaver-girl-plugins/. Kept as a flat list so the
 * site cannot silently drift into advertising a subset as if it were the whole product.
 */
export const allPlugins: string[] = [
  "activej", "async", "asynchttp", "avaje", "awslambda", "awssdk", "awssdk2", "azurecore",
  "c3p0", "caffeine", "camel", "cassandra", "clickhouse", "commonshttp", "couchbase", "cxf",
  "dbcp", "dropwizard", "druid", "dubbo", "elasticapm", "elasticjob", "elasticsearch",
  "estransport", "exception", "feign", "finatra", "geode", "ghttp", "googlehttp", "grails",
  "graphql", "grizzly", "grpc", "gwt", "hbase", "hibernate", "hibernatesearch", "hikari",
  "httpasync", "httpclient", "httpclient5", "hystrix", "influxdb", "javalin", "jaxrs", "jaxws",
  "jdbc", "jedis", "jersey", "jetty", "jetty12http", "jettyhttp", "jfinal", "jms", "jodd",
  "jsf", "jsp", "kafka", "kotlin", "kubernetes", "lettuce", "liberty", "logging", "memcached",
  "mongo", "mybatis", "nats", "netty", "ninghttp", "okhttp", "opensearch", "play", "playws",
  "pool", "powerjob", "pulsar", "quarkus", "quartz", "rabbitmq", "ratpack", "reactor", "redis",
  "rediscala", "redisson", "resteasy", "restlet", "resttemplate", "rocketmq", "rxjava",
  "scalaforkjoin", "scalafuture", "scheduled", "scheduling", "servlet", "sparkjava", "spring",
  "springamqp", "springbatch", "springjms", "springkafka", "springmvc", "springpulsar",
  "springrmi", "springws", "struts", "struts2", "tapestry", "thrift", "timing", "tomcat",
  "trace", "traceanno", "traced", "twilio", "undertow", "vaadin", "vertx", "vertxredis",
  "vertxsql", "vibur", "webflux", "websocket", "wicket", "withspan", "xxljob",
];

export interface RuntimeRow {
  key: string;
  name: string;
  description: string;
}

/** Optional capability jars in weaver-girl-runtime-plugins/. Each activates from its config key. */
export const runtimeCapabilities: RuntimeRow[] = [
  { key: "prometheus", name: "prometheus", description: "Prometheus 指标端点：事件计数、慢调用/错误、Agent 自身与 JVM 指标" },
  { key: "health", name: "health", description: "/health 与 /ready 探针，直接对接 Kubernetes liveness / readiness" },
  { key: "otlp", name: "otlp", description: "OTLP/HTTP JSON 导出：span、日志与 resource attributes" },
  { key: "span-export", name: "span-export", description: "拦截事件批量导出为 span，含父子关系与批处理" },
  { key: "json-events", name: "json-events", description: "结构化 JSON 事件流，便于接入现有日志管道" },
  { key: "jmx", name: "jmx", description: "JMX MBean：Agent 状态、插件与拦截器指标" },
  { key: "diagnostics", name: "diagnostics", description: "自诊断：内存趋势、拦截热点、故障检测与 /stats" },
  { key: "taint", name: "taint", description: "污点分析运行时：source / propagator / sink，IAST 的数据流底座" },
  { key: "management-api", name: "management-api", description: "REST 管理接口：Agent 状态、插件、拓扑、告警与指标" },
  { key: "annotation-scan", name: "annotation-scan", description: "启动时扫描 @WeaveClass，注解模式无需手动登记" },
  { key: "config-watch", name: "config-watch", description: "YAML 热加载：文件变更后不重启生效" },
  { key: "dynamic-refresh", name: "dynamic-refresh", description: "运行期开关与配置刷新，支持快照与回滚" },
  { key: "alert-file", name: "alert-file", description: "告警引擎落盘通道，规则命中即写文件" },
  { key: "update-check", name: "update-check", description: "版本更新检查，只在显式配置时联网" },
];

export interface Scenario {
  key: string;
  tag: string;
  title: string;
  description: string;
  outcomes: string[];
}

/** Concrete things a team builds on top of the hook base. Mirrored by the runnable demos. */
export const scenarios: Scenario[] = [
  {
    key: "apm",
    tag: "APM",
    title: "把调用链和指标接出来",
    description:
      "Servlet、RPC、数据库、缓存、消息队列的拦截事件自动串成 span，W3C traceparent 跨进程透传，OTLP 或 Prometheus 任选。",
    outcomes: ["跨服务 traceparent / baggage 透传", "服务拓扑与 p50/p95/p99 指标", "OTLP、Prometheus、JSON 事件多种出口"],
  },
  {
    key: "iast",
    tag: "IAST",
    title: "在运行时跟踪污点数据流",
    description:
      "taint 能力把 source、propagator、sink 串起来，配合方法/字段拦截，在不改代码的前提下发现注入类风险。",
    outcomes: ["请求参数到 SQL / 命令执行的链路", "字段读写自动传播污点", "按规则命中告警，不阻断业务"],
  },
  {
    key: "rasp",
    tag: "RASP",
    title: "把运行时防护做成插件",
    description:
      "在危险调用前后做判定：命中规则时跳过方法、改写返回值或替换异常。底座提供切点、上下文、隔离与熔断；规则库与阻断白名单属于应用层，本仓库不内置。",
    outcomes: ["方法跳过、返回值改写、异常替换", "字段写拒绝与 catch 抑制", "插件级隔离，规则出错不拖垮业务"],
  },
  {
    key: "security",
    tag: "数据安全",
    title: "脱敏与审计做成插件",
    description:
      "插件可以在方法返回前改写敏感字段，也可以统计每一次脱敏；业务代码完全不知道插件存在。仓库自带 support-desk 演示可复现。",
    outcomes: ["卡号、手机号返回前脱敏", "脱敏次数进指标而不是日志", "插件 jar 放进 plugins/ 即生效"],
  },
  {
    key: "resilience",
    tag: "业务韧性",
    title: "给没有保护的下游加一层",
    description:
      "用拦截器统计失败并在阈值后熔断，后续请求直接走降级分支。payment-gateway 演示里，同一个程序挂上插件后不再打满故障下游。",
    outcomes: ["连续失败自动打开熔断", "降级请求计数可观测", "支持按目标方法独立配置"],
  },
];

export interface CaseMetric {
  value: string;
  label: string;
}

export interface CaseStudy {
  key: string;
  tag: string;
  tagColor: "blue" | "volcano" | "purple";
  title: string;
  subtitle: string;
  scene: string;
  problem: string;
  approach: string;
  beforeLabel: string;
  before: string;
  afterLabel: string;
  after: string;
  metrics: CaseMetric[];
  value: string[];
  run: string;
  demo: string;
  sources: { label: string; href: string }[];
}

/**
 * Runnable case studies. Every program below is sample code in this repository and never imports
 * its plugin; the two outputs are copied from the scripts' real runs, not written by hand.
 */
export const caseStudies: CaseStudy[] = [
  {
    key: "redaction",
    tag: "数据安全",
    tagColor: "blue",
    title: "客服工单里的卡号，在离开系统前被掩码",
    subtitle: "同一个工单程序，业务代码一行不改；差别只是 plugins/ 目录里多了一个 jar。",
    scene:
      "客服每天把工单内容复述到聊天窗口或工单备注里。工单系统保存的是客户原文，卡号和手机号会随文本一起离开系统。",
    problem:
      "合规要求敏感信息不得明文外发，但脱敏点分散在导出、复制、对接等多个出口，靠人工检查必然有漏网之鱼。",
    approach:
      "redaction 插件按类名织入 TicketStore.render，在方法返回前用 @Advice.Return(readOnly = false) 改写返回值。工单存储保持原文，只有交出去的那一份被掩码。",
    beforeLabel: "没有插件",
    before: `what the agent would paste into chat:
  ticket 1: my card 4111111111111234 was charged twice, please refund
  ticket 2: call me back on 13812345678 about order 9001

secrets the plugin caught (0)
plugin not active: the raw card number and phone number left the process`,
    afterLabel: "挂上插件",
    after: `what the agent would paste into chat:
  ticket 1: my card 411111******1234 was charged twice, please refund
  ticket 2: call me back on 138****5678 about order 9001

secrets the plugin caught (2)
secrets masked, from the metric registry: 2
plugin active: the pasted text was redacted without the desk knowing`,
    metrics: [
      { value: "2", label: "卡号与手机号被掩码" },
      { value: "0", label: "业务代码改动行数" },
      { value: "2", label: "脱敏指标计数" },
    ],
    value: [
      "合规出口收敛到一处，以后新增的出口自动继承掩码",
      "原文留在工单里，审计和客服仍可追溯",
      "每次脱敏都进 MetricRegistry，可直接做报表和告警",
    ],
    run: "SKIP_BUILD=1 scripts/run-scenario-demos.sh",
    demo: "com.example.support.RedactionDemo",
    sources: [
      {
        label: "RedactionDemo.java",
        href: `${REPO_URL}/blob/main/weaver-girl-sample/src/main/java/com/example/support/RedactionDemo.java`,
      },
      {
        label: "RedactionPlugin.java",
        href: `${REPO_URL}/blob/main/weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/redaction/RedactionPlugin.java`,
      },
    ],
  },
  {
    key: "resilience",
    tag: "业务韧性",
    tagColor: "volcano",
    title: "故障支付通道被熔断，请求不再继续打过去",
    subtitle: "网关自己没有重试、没有降级；插件在它和下游之间补上这一层。",
    scene:
      "支付网关调用下游通道。通道在一段时间内连续超时，而这套网关是早期代码，没有熔断和降级逻辑。",
    problem:
      "六笔请求全部打到故障通道，四笔失败直接抛给用户；故障期间下游被持续打满，恢复也更慢。",
    approach:
      "resilience 插件拦截 PaymentGateway.charge：连续三次失败后打开熔断，第四笔直接返回 queued: 降级结果，不再占用下游；通道恢复后自动闭合。",
    beforeLabel: "没有插件",
    before: `charges:
  charge 1 -> failed: provider timed out on call 1
  charge 2 -> failed: provider timed out on call 2
  charge 3 -> failed: provider timed out on call 3
  charge 4 -> failed: provider timed out on call 4
  charge 5 -> approved:500
  charge 6 -> approved:600

served by the provider: 2, failed: 4, shed by the plugin: 0
the provider was actually called 6 time(s)`,
    afterLabel: "挂上插件",
    after: `charges:
  charge 1 -> failed: provider timed out on call 1
  charge 2 -> failed: provider timed out on call 2
  charge 3 -> failed: provider timed out on call 3
  charge 4 -> queued:400 (answered without the provider)
  charge 5 -> failed: provider timed out on call 4
  charge 6 -> approved:600

served by the provider: 1, failed: 4, shed by the plugin: 1
the provider was actually called 5 time(s)
charges shed, from the metric registry: 1`,
    metrics: [
      { value: "5", label: "下游实际调用次数（原 6）" },
      { value: "1", label: "被熔断降级的请求" },
      { value: "3", label: "连续失败后打开熔断" },
    ],
    value: [
      "故障期间不再放大无效流量，下游恢复更快",
      "调用方拿到明确的降级结果，而不是一串超时异常",
      "熔断与降级量进入指标，可以接告警和看板",
    ],
    run: "SKIP_BUILD=1 scripts/run-scenario-demos.sh",
    demo: "com.example.gateway.ResilienceDemo",
    sources: [
      {
        label: "ResilienceDemo.java",
        href: `${REPO_URL}/blob/main/weaver-girl-sample/src/main/java/com/example/gateway/ResilienceDemo.java`,
      },
      {
        label: "ResiliencePlugin.java",
        href: `${REPO_URL}/blob/main/weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/resilience/ResiliencePlugin.java`,
      },
    ],
  },
  {
    key: "audit",
    tag: "业务审计",
    tagColor: "purple",
    title: "支付审计线旁路生成，核心链路零改动",
    subtitle: "财务要逐笔审计，支付服务不用排期改代码，插件自己把数据取走。",
    scene:
      "支付服务处理扣款和退款。财务与风控需要逐笔审计线，但服务里原本没有任何埋点。",
    problem:
      "在核心支付链路里加审计代码需要排期、回归和发布；任何一处遗漏，都会让对账缺数据。",
    approach:
      "audit 插件按类名监听 PaymentService，在拦截事件里脱敏卡号、把扣款金额累加到 MetricRegistry，并通过自己的 DataExporter 输出审计行；被拒绝的扣款不计入。",
    beforeLabel: "没有插件",
    before: `service rejected a bad charge: amount must be positive, got -1

  Payment{id=1, card=4111111111111234, amount=2599, status=REFUNDED}
  Payment{id=2, card=5500000000009876, amount=750, status=CHARGED}

audit events the plugin published (0)
cents charged, from the metric registry: 0
plugin not active: run this under the agent with the plugin jar in plugins/`,
    afterLabel: "挂上插件",
    after: `[payment-audit] charge card=4111********1234 cents=2599 in 4ms
[payment-audit] charge card=5500********9876 cents=750 in 0ms
[payment-audit] refund payment=1 in 0ms
service rejected a bad charge: amount must be positive, got -1

audit events the plugin published (3)
cents charged, from the metric registry: 3349
plugin active: the audit trail was produced without the service calling it`,
    metrics: [
      { value: "3", label: "审计事件（2 扣款 + 1 退款）" },
      { value: "3349", label: "扣款金额合计（分）" },
      { value: "0", label: "支付服务改动行数" },
    ],
    value: [
      "审计线旁路生成，核心支付链路不用重新发布",
      "敏感卡号在审计输出前已脱敏，财务可直接查看",
      "被拒绝的扣款自动排除，金额汇总可直接对账",
    ],
    run: "SKIP_BUILD=1 scripts/run-audit-demo.sh",
    demo: "com.example.payments.AuditDemo",
    sources: [
      {
        label: "AuditDemo.java",
        href: `${REPO_URL}/blob/main/weaver-girl-sample/src/main/java/com/example/payments/AuditDemo.java`,
      },
      {
        label: "AuditPlugin.java",
        href: `${REPO_URL}/blob/main/weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/audit/AuditPlugin.java`,
      },
    ],
  },
];

export const stats = [
  { value: "126", unit: "个", label: "内置插桩插件" },
  { value: "14", unit: "个", label: "可选运行时能力" },
  { value: "3", unit: "种", label: "接入写法" },
  { value: "8–21", unit: "", label: "JDK 版本覆盖" },
];

export const benchmarks = [
  { value: "≈1.2 ns", label: "精确类/方法匹配" },
  { value: "≈25 ns", label: "熔断检查" },
  { value: "3,671", label: "自动化测试" },
  { value: "0.0", label: "SpotBugs High / Checkstyle 违规" },
];

export const quickStart = `git clone ${REPO_URL}.git
cd weaver-girl
mvn clean package -DskipTests

# target/plugins/ 必须与 agent jar 放在一起（发行包已打包好）
java -javaagent:weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar \\
  -jar your-app.jar`;

export const releaseInstall = `# GitHub Release 提供 weaver-girl-agent-<version>-dist.zip
# 内含 agent jar 与它需要的 plugins/ 目录
unzip weaver-girl-agent-1.0.0-dist.zip
java -javaagent:weaver-girl-agent-1.0.0/weaver-girl-agent-1.0.0.jar \\
  -jar your-app.jar`;

export const containerInstall = `# 构建产物就绪后，用仓库自带 Dockerfile 把 agent 与 plugins/ 一起打进镜像
mvn clean package -DskipTests
docker build -t my-app-with-weaver .

# 运行时指定业务 jar；agent 已通过 JAVA_AGENT_OPTS 挂好
docker run --rm -e APP_JAR=/opt/app/app.jar my-app-with-weaver`;

export const configExample = `# 只拦截自己的代码
onlyInterceptPackages:
  - com.myapp

# 按名称关闭不需要的插件
disabledPlugins:
  - servlet

# 或者反过来，只加载列出的能力
# enabledPlugins:
#   - jdbc
#   - servlet

# 自定义拦截器
interceptors:
  - className: com.myapp.service.UserService
    method: createUser
    before: com.myapp.interceptor.AuditAdvice`;

export const programmaticExample = `@Override
public void registerInterceptors(InterceptorRegistry registry) {
    intercept("com.example.Service")
        .method("process")
        .before(inv -> log.debug("Before: {}", inv.getMethodName()))
        .after(inv -> log.debug("After: {}", inv.getMethodName()))
        .register(registry);
}`;

export const annotationExample = `@WeaveClass(className = "com.example.Service")
public class MyInterceptor {

    @Before(methodName = "process")
    public static void beforeProcess(MethodInvocation invocation) {
        // Service.process() 执行前
    }

    @After(methodName = "process")
    public static void afterProcess(MethodInvocation invocation) {
        // Service.process() 执行后
    }
}`;

export const pluginDependencyExample = `<dependency>
  <groupId>com.github.cc11001100</groupId>
  <artifactId>weaver-girl-api</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>`;

export const observabilityExample = `java -javaagent:agent.jar=metricsPort=9400,healthPort=9401 -jar app.jar

curl http://localhost:9401/health   # 存活探针
curl http://localhost:9401/ready    # 就绪探针
curl http://localhost:9400/metrics  # Prometheus 指标`;

export interface DocLink {
  title: string;
  description: string;
  href: string;
}

export const docLinks: DocLink[] = [
  { title: "架构说明", description: "模块划分、类加载隔离与织入流程", href: `${REPO_URL}/blob/main/docs/ARCHITECTURE.md` },
  { title: "切面底座定位", description: "为什么本仓库只做底座，APM / IAST / RASP 属于切面应用", href: `${REPO_URL}/blob/main/docs/ant-aspect-base-decision.md` },
  { title: "插件开发指南", description: "从零编写并发布一个 WeaverPlugin", href: `${REPO_URL}/blob/main/docs/plugin-developer-guide.md` },
  { title: "YAML 配置参考", description: "全部配置项与默认值", href: `${REPO_URL}/blob/main/docs/yaml-config-reference.md` },
  { title: "可观测性指南", description: "追踪、指标、OTLP 与告警的完整目录", href: `${REPO_URL}/blob/main/docs/observability.md` },
  { title: "运维手册", description: "Kubernetes、Docker 与生产部署", href: `${REPO_URL}/blob/main/docs/runbook.md` },
  { title: "故障排查", description: "常见挂载与拦截问题的定位方法", href: `${REPO_URL}/blob/main/docs/troubleshooting.md` },
  { title: "扩展点指南", description: "采样、导出、告警等 SPI 的扩展方式", href: `${REPO_URL}/blob/main/docs/extension-point-guide.md` },
  { title: "性能基线", description: "各热路径组件的 ns/op 与回归守卫", href: `${REPO_URL}/blob/main/docs/benchmarks/baseline-v1.md` },
  { title: "变更记录", description: "每个版本做了什么", href: `${REPO_URL}/blob/main/CHANGELOG.md` },
];

export interface ModuleRow {
  key: string;
  name: string;
  description: string;
}

export const modules: ModuleRow[] = [
  { key: "api", name: "weaver-girl-api", description: "插件 SDK：第三方插件唯一依赖的模块，含拦截、追踪、指标、导出与污点分析" },
  { key: "core", name: "weaver-girl-core", description: "引擎：ByteBuddy 转换器、注册表、配置、熔断与采样" },
  { key: "annotation", name: "weaver-girl-annotation", description: "声明式注解：@WeaveClass、@Before、@After、@Around" },
  { key: "annotation-runtime", name: "weaver-girl-annotation-runtime", description: "注解切面的加载与织入运行时" },
  { key: "plugins", name: "weaver-girl-plugin-*", description: "126 个内置插桩插件，每个插件一个独立模块，只依赖 api" },
  { key: "runtime-plugins", name: "weaver-girl-runtime-*", description: "14 个可选运行时能力（指标、健康检查、OTLP、热加载、污点分析等），配置了才加载" },
  { key: "bridge", name: "weaver-girl-bridge-*", description: "可选桥接：Micrometer 指标与 AOP Alliance 拦截器" },
  { key: "agent", name: "weaver-girl-agent", description: "Agent 入口：premain / agentmain、插件目录发现与 YAML 配置加载" },
  { key: "testkit", name: "weaver-girl-testkit", description: "进程内测试工具，插件作者无需真实框架即可验证织入行为" },
  { key: "sample", name: "weaver-girl-sample", description: "示例应用，演示编程式、注解与 YAML 三种模式" },
];
