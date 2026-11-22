#!/usr/bin/env bash
#
# Proves two more hook types: call-site tracking and taint (IAST) findings.
#
#   call site — a shared ledger records who called it: class, method and line, captured by
#               `trackCallSite(true)` once per call.
#   taint     — a request parameter is concatenated into a SQL string. With `taint=true` the
#               concatenation carries the mark forward and the SQL sink reports which slice of the
#               query came from the request.
#
# The application references neither the plugin nor the taint API beyond marking its input, the
# same call the servlet plugin's capture makes for a real request. Under the agent the demo exits 0;
# the same program without the agent exits 2.
#
# Usage:
#   scripts/run-taint-demo.sh            # build, then run both ways
#   SKIP_BUILD=1 scripts/run-taint-demo.sh
#
set -euo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"

MVNW="${MVNW:-./mvnw}"
GATE_SKIPS="-Djacoco.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dmaven.javadoc.skip=true"

AGENT_JAR="weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar"
SAMPLE_JAR="weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT.jar"
API_JAR="weaver-girl-api/target/weaver-girl-api-1.0.0-SNAPSHOT.jar"
PLUGIN_JAR="weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT-callsite-trace-plugin.jar"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "Building agent, api and the taint/call-site demo..."
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
echo "=== with the agent: call-site tracking and taint in one run ==="
set +e
java -javaagent:"$AGENT_JAR"=plugins="$STAGE/plugins",taint=true -cp "$SAMPLE_JAR:$API_JAR" \
  com.example.search.InjectionDemo
WITH_AGENT=$?
set -e
if [ "$WITH_AGENT" -ne 0 ]; then
  echo "FAIL: expected exit 0 with the agent, got $WITH_AGENT" >&2
  exit 1
fi

echo
echo "=== without the agent: the same program, the query is still built from the raw parameter ==="
set +e
java -cp "$SAMPLE_JAR:$API_JAR" com.example.search.InjectionDemo
WITHOUT_AGENT=$?
set -e
if [ "$WITHOUT_AGENT" -ne 2 ]; then
  echo "FAIL: expected exit 2 without the agent, got $WITHOUT_AGENT" >&2
  exit 1
fi

echo
echo "taint and call-site demo ok: exit 0 with the agent and 2 without it"
