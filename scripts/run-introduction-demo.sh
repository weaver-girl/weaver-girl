#!/usr/bin/env bash
#
# Proves introduction (mixin) support: a plugin adds an interface to a class it does not own.
#
#   csv export — an invoice has no CSV support. With the plugin, the invoice's class implements
#                InvoiceCsv, `instanceof` answers true, and toCsvRow() renders a real row through
#                the plugin's delegate. Neither the class nor the demo references the plugin.
#
# The application never references the plugin. The plugin jar is discovered only because it sits in
# plugins/ and carries the WeaverPlugin service file. Under the agent the demo exits 0; the same
# program without the agent exits 2.
#
# Note: an introduction is applied when the target type is first defined. A type the JVM has
# already loaded cannot gain an interface (a JVM limit), so the demo asks the object before it does
# anything else.
#
# Usage:
#   scripts/run-introduction-demo.sh            # build, then run both ways
#   SKIP_BUILD=1 scripts/run-introduction-demo.sh
#
set -euo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"

MVNW="${MVNW:-./mvnw}"
GATE_SKIPS="-Djacoco.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dmaven.javadoc.skip=true"

AGENT_JAR="weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar"
SAMPLE_JAR="weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT.jar"
PLUGIN_JAR="weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT-csv-export-plugin.jar"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "Building agent and the introduction demo..."
  "$MVNW" -o -pl weaver-girl-agent,weaver-girl-sample -am package \
    -DskipTests $GATE_SKIPS -q
fi

for jar in "$AGENT_JAR" "$SAMPLE_JAR" "$PLUGIN_JAR"; do
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
  com.example.billing.IntroductionDemo
WITH_AGENT=$?
set -e
if [ "$WITH_AGENT" -ne 0 ]; then
  echo "FAIL: expected exit 0 with the agent, got $WITH_AGENT" >&2
  exit 1
fi

echo
echo "=== without the agent: the same program, the class keeps its original interface list ==="
set +e
java -cp "$SAMPLE_JAR" com.example.billing.IntroductionDemo
WITHOUT_AGENT=$?
set -e
if [ "$WITHOUT_AGENT" -ne 2 ]; then
  echo "FAIL: expected exit 2 without the agent, got $WITHOUT_AGENT" >&2
  exit 1
fi

echo
echo "introduction demo ok: exit 0 with the agent and 2 without it"
