#!/usr/bin/env bash
#
# Builds this example, unpacks the released agent distribution, attaches it and shows the
# endpoints it exposes next to the application's own.
#
#   ./run.sh                 # build, attach, print the demo URLs, then wait for Ctrl-C
#   AGENT_ZIP=/path/to/weaver-girl-agent-1.0.0-dist.zip ./run.sh
#   AGENT_VERSION=1.0.0 ./run.sh          # release to download when AGENT_ZIP is unset
#   PORT=18080 ./run.sh                   # application port (the agent's ports shift with it)
#
# The agent distribution is a zip, not a bare jar: the agent discovers its plugins from the
# plugins/ directory that sits next to the jar. Attaching the jar alone instruments nothing.
#
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
cd "$HERE"

AGENT_VERSION="${AGENT_VERSION:-1.0.0}"
PORT="${PORT:-8080}"
METRICS_PORT=$((PORT + 1))
HEALTH_PORT=$((PORT + 2))
DIAGNOSTICS_PORT=$((PORT + 3))
AGENT_HOME="${AGENT_HOME:-$HERE/target/agent}"

# --- 1. the example itself, against released coordinates only -------------------------------
echo "==> Building the example (Spring Boot ${BOOT_VERSION:-3.3.5} + H2, no weaver-girl dependency)"
"$HERE/../../mvnw" -B -q -f "$HERE/pom.xml" package -DskipTests

# --- 2. the agent distribution -----------------------------------------------------------
if [ -n "${AGENT_ZIP:-}" ]; then
  ZIP="$AGENT_ZIP"
elif [ -f "$HERE/target/weaver-girl-agent-$AGENT_VERSION-dist.zip" ]; then
  ZIP="$HERE/target/weaver-girl-agent-$AGENT_VERSION-dist.zip"
else
  echo "==> Downloading weaver-girl-agent-$AGENT_VERSION-dist.zip from Maven Central"
  mkdir -p "$HERE/target"
  ZIP="$HERE/target/weaver-girl-agent-$AGENT_VERSION-dist.zip"
  curl -fsSL -o "$ZIP" \
    "https://repo1.maven.org/maven2/io/github/cc11001100/weaver-girl-agent/$AGENT_VERSION/weaver-girl-agent-$AGENT_VERSION-dist.zip"
fi

if [ ! -f "$ZIP" ]; then
  echo "ERROR: no agent distribution. Set AGENT_ZIP=/path/to/weaver-girl-agent-<version>-dist.zip" >&2
  exit 1
fi

echo "==> Unpacking $ZIP"
rm -rf "$AGENT_HOME"
mkdir -p "$AGENT_HOME"
unzip -q "$ZIP" -d "$AGENT_HOME"
AGENT_JAR=$(find "$AGENT_HOME" -maxdepth 3 -name 'weaver-girl-agent-*.jar' ! -name '*sources*' ! -name '*javadoc*' | head -1)
PLUGIN_DIR=$(find "$AGENT_HOME" -maxdepth 3 -type d -name plugins | head -1)
if [ -z "$AGENT_JAR" ] || [ ! -d "$PLUGIN_DIR" ]; then
  echo "ERROR: the distribution does not contain the agent jar and plugins/ next to it" >&2
  exit 1
fi

APP_JAR=$(ls "$HERE"/target/weaver-girl-example-*.jar | head -1)

echo
echo "======================================================================"
echo " application       http://127.0.0.1:$PORT/orders"
echo " agent /health     http://127.0.0.1:$HEALTH_PORT/health"
echo " agent /ready      http://127.0.0.1:$HEALTH_PORT/ready"
echo " agent /metrics    http://127.0.0.1:$METRICS_PORT/metrics"
echo " agent /stats      http://127.0.0.1:$DIAGNOSTICS_PORT/stats"
echo "======================================================================"
echo
echo "Try:"
echo "  curl -s localhost:$PORT/orders | head -c 200"
echo "  curl -s localhost:$PORT/orders/slow > /dev/null && sleep 1 && \\"
echo "       curl -s localhost:$METRICS_PORT/metrics | grep -E 'plugin=\"(servlet|jdbc|springmvc)\"' | head"
echo
echo "The last command prints a non-zero _count, which is the proof that the agent actually"
echo "instrumented the request, not merely that it started."
echo

exec java \
  -javaagent:"$AGENT_JAR"=plugins="$PLUGIN_DIR",metricsPort="$METRICS_PORT",healthPort="$HEALTH_PORT",diagnosticsPort="$DIAGNOSTICS_PORT" \
  -jar "$APP_JAR" --server.port="$PORT"
