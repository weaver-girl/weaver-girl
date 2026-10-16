#!/usr/bin/env bash
#
# Proves the drop-in plugin path with the payment-audit demo.
#
# The payment service never references the plugin. The plugin jar is discovered only
# because it sits in plugins/ and carries the WeaverPlugin service file. Run under the
# agent and the audit trail shows up (exit 0); run the same program without the agent
# and it does not (exit 2). That contrast is the whole point.
#
# Usage:
#   scripts/run-audit-demo.sh            # build, then run both ways
#   SKIP_BUILD=1 scripts/run-audit-demo.sh
#
set -euo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"

MVNW="${MVNW:-./mvnw}"
GATE_SKIPS="-Djacoco.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dmaven.javadoc.skip=true"

AGENT_JAR="weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar"
SAMPLE_JAR="weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT.jar"
API_JAR="weaver-girl-api/target/weaver-girl-api-1.0.0-SNAPSHOT.jar"
PLUGIN_JAR="weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT-audit-plugin.jar"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "Building agent, api and the audit demo..."
  "$MVNW" -o -pl weaver-girl-agent,weaver-girl-sample -am package \
    -DskipTests $GATE_SKIPS -q
fi

for jar in "$AGENT_JAR" "$SAMPLE_JAR" "$API_JAR" "$PLUGIN_JAR"; do
  if [ ! -f "$jar" ]; then
    echo "ERROR: $jar not found. Build first, or drop SKIP_BUILD." >&2
    exit 1
  fi
done

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/plugins"
cp "$PLUGIN_JAR" "$STAGE/plugins/"

echo
echo "=== with the agent: the plugin jar is in plugins/, nothing else wires it in ==="
set +e
java -javaagent:"$AGENT_JAR"=plugins="$STAGE/plugins" -cp "$SAMPLE_JAR" \
  com.example.payments.AuditDemo
WITH_AGENT=$?
set -e
if [ "$WITH_AGENT" -ne 0 ]; then
  echo "FAIL: expected exit 0 with the agent, got $WITH_AGENT" >&2
  exit 1
fi

echo
echo "=== without the agent: the same program, the service runs, no audit trail ==="
set +e
java -cp "$API_JAR:$SAMPLE_JAR" com.example.payments.AuditDemo
WITHOUT_AGENT=$?
set -e
if [ "$WITHOUT_AGENT" -ne 2 ]; then
  echo "FAIL: expected exit 2 without the agent, got $WITHOUT_AGENT" >&2
  exit 1
fi

echo
echo "audit demo ok: exit 0 with the agent, exit 2 without it"
