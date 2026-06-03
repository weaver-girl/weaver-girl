# Weaver-Girl Helm Chart

Installs the Weaver-Girl Java Agent as a sidecar for Kubernetes workloads.

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
| `image.repository` | `weaver-girl/agent` | Agent container image |
| `image.tag` | `1.0.0` | Image tag |
| `config.metricsPort` | `9400` | Prometheus metrics port |
| `config.healthPort` | `9401` | Health check port |
| `config.samplingRate` | `1` | Interception sampling rate |
| `config.disabledPlugins` | `""` | Comma-separated plugins to disable |
| `serviceMonitor.enabled` | `false` | Enable Prometheus ServiceMonitor |
| `yamlConfig` | `""` | Weaver-Girl YAML configuration |

## Using as Init Container

To inject the agent into an existing deployment:

```yaml
# In your deployment spec:
initContainers:
  - name: copy-agent
    image: weaver-girl/agent:1.0.0
    command: ['sh', '-c', 'cp /opt/weaver-girl/*.jar /agent/']
    volumeMounts:
      - name: agent-volume
        mountPath: /agent
containers:
  - name: my-app
    env:
      - name: JAVA_TOOL_OPTIONS
        value: "-javaagent:/agent/weaver-girl-agent.jar=metricsPort=9400,healthPort=9401"
    volumeMounts:
      - name: agent-volume
        mountPath: /agent
volumes:
  - name: agent-volume
    emptyDir: {}
```

## Health Checks

The chart configures Kubernetes liveness and readiness probes against the agent's health endpoint:

- **Liveness**: `GET /health` — returns 200 when agent is running
- **Readiness**: `GET /ready` — returns 200 when interceptors are registered
