{{/*
Expand the name of the chart.
*/}}
{{- define "weaver-girl.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "weaver-girl.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "weaver-girl.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "weaver-girl.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "weaver-girl.selectorLabels" -}}
app.kubernetes.io/name: {{ include "weaver-girl.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
The agent argument string, rendered from .Values.config.

The agent reads sampling/bind/port settings from its argument list (comma-separated key=value
pairs after the jar path), not from weaver.yml. Keeping the mapping here means `--set
config.metricsPort=9400` actually reaches the agent.
*/}}
{{- define "weaver-girl.agentArgs" -}}
{{- $pairs := list -}}
{{- if .Values.config.metricsPort }}{{ $pairs = append $pairs (printf "metricsPort=%v" .Values.config.metricsPort) }}{{ end -}}
{{- if .Values.config.healthPort }}{{ $pairs = append $pairs (printf "healthPort=%v" .Values.config.healthPort) }}{{ end -}}
{{- if .Values.config.diagnosticsPort }}{{ $pairs = append $pairs (printf "diagnosticsPort=%v" .Values.config.diagnosticsPort) }}{{ end -}}
{{- if .Values.config.watch }}{{ $pairs = append $pairs "watch=true" }}{{ end -}}
{{- if .Values.config.jsonEvents }}{{ $pairs = append $pairs "jsonEvents=true" }}{{ end -}}
{{- if .Values.config.samplingRate }}{{ $pairs = append $pairs (printf "samplingRate=%v" .Values.config.samplingRate) }}{{ end -}}
{{- if .Values.config.samplingMaxRate }}{{ $pairs = append $pairs (printf "samplingMaxRate=%v" .Values.config.samplingMaxRate) }}{{ end -}}
{{- if .Values.config.samplingThreshold }}{{ $pairs = append $pairs (printf "samplingThreshold=%v" .Values.config.samplingThreshold) }}{{ end -}}
{{- if .Values.config.circuitBreakerThreshold }}{{ $pairs = append $pairs (printf "circuitBreakerThreshold=%v" .Values.config.circuitBreakerThreshold) }}{{ end -}}
{{- if .Values.config.circuitBreakerCooldownMs }}{{ $pairs = append $pairs (printf "circuitBreakerCooldownMs=%v" .Values.config.circuitBreakerCooldownMs) }}{{ end -}}
{{- if .Values.config.disabledPlugins }}{{ $pairs = append $pairs (printf "disabledPlugins=%s" .Values.config.disabledPlugins) }}{{ end -}}
{{- if .Values.config.endpointBindAddress }}{{ $pairs = append $pairs (printf "endpointBindAddress=%v" .Values.config.endpointBindAddress) }}{{ end -}}
{{- if .Values.config.endpointMaxThreads }}{{ $pairs = append $pairs (printf "endpointMaxThreads=%v" .Values.config.endpointMaxThreads) }}{{ end -}}
{{- if .Values.config.endpointAuthToken }}{{ $pairs = append $pairs (printf "endpointAuthToken=%s" .Values.config.endpointAuthToken) }}{{ end -}}
{{- if .Values.config.configFile }}{{ $pairs = append $pairs (printf "config=%s" .Values.config.configFile) }}{{ end -}}
{{- join "," $pairs -}}
{{- end }}
