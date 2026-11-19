# Weaver-Girl Helm Chart

Creates the Service, ServiceMonitor and configuration ConfigMap for workloads
that already run the Weaver-Girl Java agent. The chart does **not** inject the
agent into pods yet; use the init-container pattern below. A mutating-webhook
injector is on the roadmap.

## Quick Start

```bash
# Install the chart
helm install weaver-girl ./deploy/helm/weaver-girl \
  --set targetApplication.name=my-app

# With custom configuration
helm install weaver-girl ./deploy/helm/weaver-girl \
  --set config.metricsPort=9400 \
  --set config.healthPort=9401 \
  --set config.jsonEvents=true \
  --set serviceMonitor.enabled=true
```

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `image.repository` | `ghcr.io/weaver-girl/weaver-girl-agent` | Agent container image |
| `image.tag` | `1.0.0` | Image tag |
| `config.metricsPort` | `9400` | Prometheus metrics port |
| `config.healthPort` | `9401` | Health check port |
| `config.samplingRate` | `1` | Interception sampling rate |
| `config.disabledPlugins` | `""` | Comma-separated plugins to disable |
| `config.diagnosticsPort` | `9402` | Diagnostics endpoint (`/stats`, `POST /agent/interception`) |
| `config.endpointBindAddress` | `0.0.0.0` | Bind address for all three endpoints; the chart exposes them through its Service |
| `config.endpointAuthToken` | `""` | Bearer token the endpoints require; pass it with `--set-string`, never in a committed file |
| `config.endpointMaxThreads` | `16` | Request-thread ceiling per endpoint server |

The token is rendered into the same argument string, which means it appears in the process
command line (`ps`). That is the same exposure as any `-D` property; it is not worse than the
image's own `JAVA_AGENT_OPTS`, but do not treat it as a secret at rest. A future release replaces
the argument with a file-based option.

The `config.*` values are rendered into an `agent-args` ConfigMap key by
`templates/_helpers.tpl` (`weaver-girl.agentArgs`). They are **agent arguments**, not
`weaver.yml` keys: the agent reads ports/binding/sampling from its argument list, and a value
placed only in `weaver.yml` has no effect for those settings. The init container picks the
string up from the ConfigMap — see the example below.
| `serviceMonitor.enabled` | `false` | Enable Prometheus ServiceMonitor |
| `yamlConfig` | `""` | Weaver-Girl YAML configuration |

## Using as Init Container

To inject the agent into an existing deployment:

```yaml
# In your deployment spec:
initContainers:
  - name: copy-agent
    image: ghcr.io/weaver-girl/weaver-girl-agent:1.0.0
    # Copy the jar AND the plugins/ directory next to it. The agent jar contains no
    # built-in plugin classes; it discovers them from plugins/ at startup.
    command: ['sh', '-c', 'cp /opt/weaver-girl/*.jar /agent/ && cp -r /opt/weaver-girl/plugins /agent/']
    volumeMounts:
      - name: agent-volume
        mountPath: /agent
containers:
  - name: my-app
    env:
      # Read the argument string the chart rendered from its values, so `--set config.metricsPort`
      # and friends actually reach the agent instead of only changing an unused value.
      - name: WEAVER_AGENT_ARGS
        valueFrom:
          configMapKeyRef:
            name: <release>-weaver-girl-config
            key: agent-args
      - name: JAVA_TOOL_OPTIONS
        value: "-javaagent:/agent/weaver-girl-agent.jar=$(WEAVER_AGENT_ARGS)"
    volumeMounts:
      - name: agent-volume
        mountPath: /agent
      - name: weaver-girl-config
        mountPath: /opt/weaver-girl
volumes:
  - name: agent-volume
    emptyDir: {}
  - name: weaver-girl-config
    configMap:
      name: <release>-weaver-girl-config
```

## Health Checks

The chart configures Kubernetes liveness and readiness probes against the agent's health endpoint:

- **Liveness**: `GET /health` — returns 200 when agent is running
- **Readiness**: `GET /ready` — returns 200 when interceptors are registered

`/ready` answers 503 until at least one interceptor is registered. That is the right signal for
"instrumentation has taken effect", but it is the wrong probe for a pod that legitimately has no
hooks (a sidecar-only deployment, or a profile where every capability jar is disabled): those pods
never become Ready. In that case point the readiness probe at `/health` instead, which answers
while the agent is up.

The probes expect the target workload container to name its ports `metrics` and `health`
(`ports: [{name: health, containerPort: 9401}, ...]`) — the chart's Service and the probe snippets
use those names, and the chart does not create the workload itself.
