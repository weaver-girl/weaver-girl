# Weaver-Girl Operations Runbook

> 生产环境运维手册 — 安装、配置、监控、故障排查

## 1. 快速安装

```bash
# 1. 构建 Agent JAR
mvn clean package -DskipTests -pl weaver-girl-agent -am

# 2. 启动应用并挂载 Agent
java -javaagent:weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar \
     -jar your-app.jar
```

## 2. Agent 参数参考

```bash
java -javaagent:weaver-girl-agent.jar=\
config=/path/to/weaver.yml,\
watch=true,\
metricsPort=9400,\
healthPort=9401,\
jsonEvents=true,\
disabledPlugins=servlet,kafka,\
samplingRate=1,\
circuitBreakerThreshold=5,\
circuitBreakerCooldownMs=60000,\
debug=true \
-jar your-app.jar
```

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `config` | 无 | YAML 配置文件路径 |
| `watch` | false | 配置热重载（文件变更自动更新） |
| `metricsPort` | 无 | Prometheus 指标端口（如 9400） |
| `otlpEndpoint` | 无 | OTLP Span 导出端点（如 http://collector:4318/v1/traces），配置后自动批量导出 Trace Span |
| `otlpHeaders` | 无 | OTLP 导出附加请求头（`Key=Value;Key2=Value2`，如鉴权头） |
| `spanExport` | false | 无 endpoint 时仅日志输出 Span（`spanExport=true`） |
| `spanExportBatchSize` | 100 | Span 批量导出大小 |
| `spanExportIntervalMs` | 5000 | Span 导出周期（毫秒） |
| `spanExportBufferSize` | 10000 | Span 缓冲区上限（满则丢弃并计数） |
| `healthPort` | 无 | 健康检查端口（如 9401） |
| `jsonEvents` | false | 结构化 JSON 事件输出到 stdout |
| `disabledPlugins` | 无 | 禁用的插件列表（逗号分隔） |
| `samplingRate` | 1 | 采样率（1=全部拦截，10=1/10） |
| `samplingMaxRate` | 100 | 最大采样率（高负载时上限） |
| `samplingThreshold` | 10000 | 自适应采样阈值（invocations/sec） |
| `circuitBreakerThreshold` | 5 | 熔断器打开的连续失败次数 |
| `circuitBreakerCooldownMs` | 60000 | 熔断器冷却时间（毫秒） |
| `debug` | false | 诊断模式 |

## 3. 健康检查端点

启用 `healthPort` 后，Agent 暴露两个 HTTP 端点：

### /health — 存活探针 (Liveness)

```bash
curl http://localhost:9401/health
# {"status":"UP","agent":"weaver-girl","uptimeSeconds":3600}
```

- **200** = Agent 正在运行
- 用于 Kubernetes livenessProbe

### /ready — 就绪探针 (Readiness)

```bash
curl http://localhost:9401/ready
# {"status":"READY","interceptorCount":12}
```

- **200** = Agent 已初始化且拦截器已注册
- **503** = Agent 未就绪（拦截器数量为 0）
- 用于 Kubernetes readinessProbe

### Kubernetes 示例

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 9401
  initialDelaySeconds: 10
  periodSeconds: 30
readinessProbe:
  httpGet:
    path: /ready
    port: 9401
  initialDelaySeconds: 5
  periodSeconds: 10
```

## 4. Prometheus 指标

启用 `metricsPort=9400` 后：

```bash
curl http://localhost:9400/metrics
```

| 指标 | 类型 | 说明 |
|------|------|------|
| `weavergirl_slow_operations_total` | counter | 慢操作计数（按 plugin、type） |
| `weavergirl_error_operations_total` | counter | 错误操作计数（按 plugin） |
| `weavergirl_operation_duration_ms_sum` | counter | 累计操作耗时（按 plugin） |
| `weavergirl_operation_duration_ms_count` | counter | 操作总次数（按 plugin） |

### Grafana 查询示例

```promql
# 慢操作速率
rate(weavergirl_slow_operations_total[5m])

# 平均操作耗时
weavergirl_operation_duration_ms_sum / weavergirl_operation_duration_ms_count

# 错误率
rate(weavergirl_error_operations_total[5m])
```

## 5. JMX 监控

Agent 自带 JMX MBean：`com.github.cc11001100.weavergirl:type=Agent`

| 属性 | 说明 |
|------|------|
| `InterceptorInvocationCount` | 拦截调用总次数 |
| `InterceptorErrorCount` | 拦截错误总次数 |
| `InterceptorDefinitionCount` | 已注册拦截器定义数 |
| `TransformedClassCount` | 已转换的类数量 |
| `TotalInterceptTimeMs` | 累计拦截耗时（毫秒） |

```bash
# 使用 jconsole 或 jmc 连接查看
# 或使用命令行：
jcmd <pid> JMX.getAttributes com.github.cc11001100.weavergirl:type=Agent
```

## 6. 常见故障排查

### Agent 未生效

**症状**: 拦截器不工作，日志中无 `[weaver-girl]` 输出

**排查步骤**:
1. 确认 `-javaagent` 参数位置正确（在 `-jar` 之前）
2. 检查 Agent JAR 文件是否完整：`jar tf weaver-girl-agent.jar | grep Premain-Class`
3. 查看应用启动日志是否有 `[weaver-girl] FATAL` 错误
4. 确认目标类在 `excludedClasses` 范围之外

### 熔断器打开

**症状**: 日志显示 `Circuit breaker OPEN for interceptor 'xxx'`

**处理**:
1. 查看插件实现是否有 bug（检查 before/after 中的异常）
2. 临时调整阈值：`circuitBreakerThreshold=20`
3. 等待冷却期（默认 60 秒）后自动恢复

### 采样过于激进

**症状**: 大量请求未被拦截

**处理**:
1. 检查 `samplingRate` 设置，设为 1 表示全量拦截
2. 检查自适应采样是否因高负载自动提升：`samplingThreshold` 默认 10000
3. 禁用自适应采样：`samplingMaxRate=1`

### 内存使用过高

**症状**: JVM 堆内存增长

**排查步骤**:
1. 检查是否有过多的拦截器定义：通过 JMX 查看 `InterceptorDefinitionCount`
2. 检查事件监听器是否有内存泄漏
3. 使用 `disabledPlugins` 禁用不需要的插件
4. 调高采样率减少拦截频率

### 配置热重载失败

**症状**: 修改 YAML 后未生效

**排查**:
1. 确认启动时使用了 `watch=true`
2. 检查配置文件路径是否正确
3. 确认 YAML 语法无误
4. 查看日志中 `ConfigWatcher` 相关输出

## 7. 性能调优

### 最小开销配置

```bash
# 只启用必要的插件，高采样率
java -javaagent:agent.jar=\
disabledPlugins=logging,timing,\
samplingRate=100,\
metricsPort=9400 \
-jar app.jar
```

### 全量监控配置

```bash
# 所有插件，全量拦截，健康检查+指标+JSON事件
java -javaagent:agent.jar=\
metricsPort=9400,\
healthPort=9401,\
jsonEvents=true \
-jar app.jar
```

## 8. 动态挂载（无需重启）

```bash
# 查找目标 JVM PID
jps -l

# 动态挂载 Agent
java -jar weaver-girl-agent.jar <pid> "config=/path/to/weaver.yml"

# 已转换的类需要 retransform
# Agent 会自动执行 retransformLoadedClasses()
```

## 9. 升级指南

1. 构建新版本 Agent JAR
2. 替换旧版本 JAR 文件
3. 重启应用（或使用动态挂载）
4. 验证：`curl http://localhost:9401/health`

## 10. 紧急回滚

如果 Agent 导致应用异常：

```bash
# 1. 移除 -javaagent 参数
# 2. 重启应用
# 3. Agent 的所有字节码修改在 JVM 重启后自动失效
# 4. 应用代码本身不受任何影响
```
