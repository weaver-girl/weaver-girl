/** Static site content. Mirrors README.md and docs/ARCHITECTURE.md. */

export const REPO_URL = "https://github.com/cc11001100/weaver-girl";

export interface Feature {
  title: string;
  description: string;
}

export const features: Feature[] = [
  {
    title: "16 个内置插件",
    description:
      "Servlet、Spring、JDBC、Redis、Kafka、gRPC、MongoDB、HttpClient、HikariCP、OkHttp、RabbitMQ、Elasticsearch 等开箱即用，SPI 自动发现。",
  },
  {
    title: "三种接入方式",
    description:
      "编程式 API、注解驱动、YAML 配置，同一套拦截器注册表，按场景选最顺手的写法。",
  },
  {
    title: "零分配字节码织入",
    description:
      "基于 ByteBuddy Advice 内联拦截，支持跳过原方法与改写返回值，热路径上不产生额外对象。",
  },
  {
    title: "动态挂载与热加载",
    description:
      "agentmain 运行时挂载并重转换已加载类；YAML 变更无需重启即可生效。",
  },
  {
    title: "生产级自我保护",
    description:
      "熔断器自动摘除异常拦截器，自适应采样在高负载下降开销，故障隔离保证被拦截应用不受影响。",
  },
  {
    title: "可观测性全套",
    description:
      "分布式追踪、W3C Trace Context、服务拓扑、告警引擎、Prometheus 指标、健康检查与 OpenTelemetry 桥接。",
  },
];

export interface PluginRow {
  key: string;
  name: string;
  target: string;
  config: string;
}

export const plugins: PluginRow[] = [
  { key: "servlet", name: "servlet", target: "javax.servlet", config: "slowRequestThreshold" },
  { key: "spring", name: "spring", target: "Spring Framework", config: "slowThreshold" },
  { key: "jdbc", name: "jdbc", target: "java.sql", config: "slowQueryThreshold, logSql" },
  { key: "redis", name: "redis", target: "Jedis / Lettuce", config: "slowCommandThreshold" },
  { key: "httpclient", name: "httpclient", target: "Apache HttpClient", config: "slowThreshold" },
  { key: "grpc", name: "grpc", target: "io.grpc", config: "slowThreshold" },
  { key: "kafka", name: "kafka", target: "Apache Kafka", config: "slowThreshold" },
  { key: "mongo", name: "mongo", target: "MongoDB Driver", config: "slowThreshold" },
  { key: "hikari", name: "hikari", target: "HikariCP", config: "leakThresholdMs, trackAcquisition" },
  { key: "okhttp", name: "okhttp", target: "OkHttp 3.x / 4.x", config: "slowThreshold, trackConnectionPool" },
  { key: "rabbitmq", name: "rabbitmq", target: "RabbitMQ Client", config: "slowPublishThreshold, slowConsumeThreshold" },
  { key: "elasticsearch", name: "elasticsearch", target: "ES REST / Java Client", config: "slowQueryThreshold, trackBulkSize" },
  { key: "timing", name: "timing", target: "任意方法", config: "slowThreshold" },
  { key: "trace", name: "trace-correlation", target: "HTTP headers", config: "headerName" },
  { key: "exception", name: "exception", target: "任意异常", config: "maxStackTraceLength" },
  { key: "logging", name: "logging", target: "SLF4J / Log4j2", config: "logLevel" },
];

export interface ModuleRow {
  key: string;
  name: string;
  description: string;
}

export const modules: ModuleRow[] = [
  { key: "api", name: "weaver-girl-api", description: "插件 SDK：接口、匹配器、上下文与事件体系" },
  { key: "core", name: "weaver-girl-core", description: "引擎：ByteBuddy 转换器、注册表、配置、熔断与采样" },
  { key: "annotation", name: "weaver-girl-annotation", description: "声明式注解：@WeaveClass、@Before、@After、@Around" },
  { key: "runtime", name: "weaver-girl-annotation-runtime", description: "注解切面的加载与织入运行时" },
  { key: "plugins", name: "weaver-girl-plugins", description: "16 个内置插桩插件" },
  { key: "agent", name: "weaver-girl-agent", description: "Agent 入口：premain / agentmain 与 YAML 配置加载" },
  { key: "sample", name: "weaver-girl-sample", description: "示例应用，演示编程式、注解与 YAML 三种模式" },
];

export const quickStart = `mvn clean package -DskipTests

java -javaagent:weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar \\
  -jar your-app.jar`;

export const configExample = `# 只拦截自己的代码
onlyInterceptPackages:
  - com.myapp

# 关闭不需要的内置插件
disabledPlugins:
  - servlet

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
  { title: "插件开发指南", description: "从零编写并发布一个 WeaverPlugin", href: `${REPO_URL}/blob/main/docs/plugin-developer-guide.md` },
  { title: "YAML 配置参考", description: "全部配置项与默认值", href: `${REPO_URL}/blob/main/docs/yaml-config-reference.md` },
  { title: "运维手册", description: "Kubernetes、Docker 与生产部署", href: `${REPO_URL}/blob/main/docs/runbook.md` },
  { title: "故障排查", description: "常见挂载与拦截问题的定位方法", href: `${REPO_URL}/blob/main/docs/troubleshooting.md` },
  { title: "变更记录", description: "每个版本做了什么", href: `${REPO_URL}/blob/main/CHANGELOG.md` },
];
