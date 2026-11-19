#!/usr/bin/env bash
#
# Shows the same log line with and without the logging plugin (issue #45).
#
# The application — com.example.support.LogCorrelationDemo — logs through SLF4J and knows
# nothing about tracing. The demo plugin jar in plugins/ bridges the active trace context
# into the logging backend for the duration of the intercepted call, so the line emitted
# inside the call carries the trace id and the one outside it does not.
#
#   without the agent   INFO  [trace  span ] startup complete
#                       INFO  [trace  span ] handling ticket 42
#   with the agent      INFO  [trace  span ] startup complete
#                       INFO  [trace 4bf92f3577b34da6a3ce929d0e0e4736 span 00f067aa0ba902b7] handling ticket 42
#
# Usage:
#   scripts/run-log-correlation-demo.sh            # build, then run both ways
#   SKIP_BUILD=1 scripts/run-log-correlation-demo.sh
#
set -euo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"

MVNW="${MVNW:-./mvnw}"
GATE_SKIPS="-Djacoco.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dmaven.javadoc.skip=true"

AGENT_JAR="weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar"
SAMPLE_JAR="weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT.jar"
PLUGIN_JAR="weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT-log-correlation-plugin.jar"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "Building agent, sample and the log-correlation demo plugin..."
  "$MVNW" -B -q -pl weaver-girl-agent,weaver-girl-sample -am package \
    -DskipTests $GATE_SKIPS
fi

for jar in "$AGENT_JAR" "$SAMPLE_JAR" "$PLUGIN_JAR"; do
  if [ ! -f "$jar" ]; then
    echo "ERROR: $jar not found. Build first, or drop SKIP_BUILD." >&2
    exit 1
  fi
done

CP="$SAMPLE_JAR:$REPO/weaver-girl-sample/target/classes"
# Jetty/H2/logback come from the module's runtime dependency set. Ask Maven for them rather than
# hard-coding file names, and fall back to a previously assembled directory.
if [ ! -d "$REPO/weaver-girl-sample/target/sample-libs" ]; then
  "$MVNW" -B -q -pl weaver-girl-sample dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/sample-libs $GATE_SKIPS
fi
CP="$CP:$REPO/weaver-girl-sample/target/sample-libs/*"

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/plugins"
cp "$PLUGIN_JAR" "$STAGE/plugins/"

echo
echo "=== with the agent: the plugin jar is in plugins/, nothing else wires it in ==="
java -javaagent:"$AGENT_JAR"=plugins="$STAGE/plugins" -cp "$CP" \
  com.example.support.LogCorrelationDemo 2>&1 | grep -vE '^\[|^[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3} \[weaver-girl'
WITH_AGENT=${PIPESTATUS[0]}

echo
echo "=== without the agent: the same program, the same lines, no trace fields ==="
set +e
java -cp "$CP" com.example.support.LogCorrelationDemo 2>&1 | grep -vE '^\['
WITHOUT_AGENT=${PIPESTATUS[0]}
set -e

if [ "$WITH_AGENT" -ne 0 ]; then
  echo
  echo "FAIL: expected the trace id to reach the MDC with the agent, exit was $WITH_AGENT" >&2
  exit 1
fi
if [ "$WITHOUT_AGENT" -ne 2 ]; then
  echo
  echo "FAIL: expected exit 2 without the agent, got $WITHOUT_AGENT" >&2
  exit 1
fi
echo
echo "PASS: the log line inside the intercepted call carried the trace id; outside the call it did not."
