#!/usr/bin/env bash
#
# Proves the two join points a method interceptor cannot express: field access and failed exit.
#
#   account guard — an account record stores a password in a plain field and prints it through the
#                   ordinary getter. With the plugin, every read of that field comes back masked,
#                   an empty write is rejected so the field keeps its previous value, and an export
#                   job that throws is answered with a degraded result instead of an exception.
#
# The application never references the plugin. The plugin jar is discovered only because it sits in
# plugins/ and carries the WeaverPlugin service file. Under the agent the demo exits 0; the same
# program without the agent exits 2.
#
# Usage:
#   scripts/run-field-catch-demo.sh            # build, then run both ways
#   SKIP_BUILD=1 scripts/run-field-catch-demo.sh
#
set -euo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"

MVNW="${MVNW:-./mvnw}"
GATE_SKIPS="-Djacoco.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dmaven.javadoc.skip=true"

AGENT_JAR="weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar"
SAMPLE_JAR="weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT.jar"
API_JAR="weaver-girl-api/target/weaver-girl-api-1.0.0-SNAPSHOT.jar"
PLUGIN_JAR="weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT-account-guard-plugin.jar"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "Building agent, api and the field/catch demo..."
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
java -javaagent:"$AGENT_JAR"=plugins="$STAGE/plugins" -cp "$SAMPLE_JAR:$API_JAR" \
  com.example.account.AccountGuardDemo
WITH_AGENT=$?
set -e
if [ "$WITH_AGENT" -ne 0 ]; then
  echo "FAIL: expected exit 0 with the agent, got $WITH_AGENT" >&2
  exit 1
fi

echo
echo "=== without the agent: the same program, the field is exposed and the export throws ==="
set +e
java -cp "$SAMPLE_JAR:$API_JAR" com.example.account.AccountGuardDemo
WITHOUT_AGENT=$?
set -e
if [ "$WITHOUT_AGENT" -ne 2 ]; then
  echo "FAIL: expected exit 2 without the agent, got $WITHOUT_AGENT" >&2
  exit 1
fi

echo
echo "field and catch demo ok: exit 0 with the agent and 2 without it"
