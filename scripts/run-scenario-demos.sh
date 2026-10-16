#!/usr/bin/env bash
#
# Proves the drop-in plugin path with two scenarios that do something visible.
#
#   ticket redaction  — a support desk stores raw card numbers and phone numbers. With the
#                       plugin, the text an agent pastes into chat comes back masked, and the
#                       stored ticket is left untouched. Without it, the secrets leave as-is.
#   gateway resilience — a payment gateway has no retry and no fallback. The provider fails its
#                       first four calls. With the plugin, the circuit opens after three failures
#                       and the fourth charge is answered from a fallback without reaching the
#                       provider. Without it, every charge hits the provider.
#
# Each application never references its plugin. The plugin jar is discovered only because it
# sits in plugins/ and carries the WeaverPlugin service file. Under the agent the demo exits 0;
# the same program without the agent exits 2.
#
# Usage:
#   scripts/run-scenario-demos.sh            # build, then run both scenarios both ways
#   SKIP_BUILD=1 scripts/run-scenario-demos.sh
#
set -euo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"

MVNW="${MVNW:-./mvnw}"
GATE_SKIPS="-Djacoco.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dmaven.javadoc.skip=true"

AGENT_JAR="weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar"
SAMPLE_JAR="weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT.jar"
API_JAR="weaver-girl-api/target/weaver-girl-api-1.0.0-SNAPSHOT.jar"
REDACTION_JAR="weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT-redaction-plugin.jar"
RESILIENCE_JAR="weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT-resilience-plugin.jar"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "Building agent, api and the scenario demos..."
  "$MVNW" -o -pl weaver-girl-agent,weaver-girl-sample -am package \
    -DskipTests $GATE_SKIPS -q
fi

for jar in "$AGENT_JAR" "$SAMPLE_JAR" "$API_JAR" "$REDACTION_JAR" "$RESILIENCE_JAR"; do
  if [ ! -f "$jar" ]; then
    echo "ERROR: $jar not found. Build first, or drop SKIP_BUILD." >&2
    exit 1
  fi
done

# Runs one main class both ways and checks the exits: 0 with the agent, 2 without.
run_scenario() {
  local title="$1" plugin_jar="$2" main_class="$3"

  local stage
  stage=$(mktemp -d)
  mkdir -p "$stage/plugins"
  cp "$plugin_jar" "$stage/plugins/"

  echo
  echo "======== $title ========"
  echo
  echo "--- with the agent: the plugin jar is in plugins/, nothing else wires it in ---"
  set +e
  java -javaagent:"$AGENT_JAR"=plugins="$stage/plugins" -cp "$SAMPLE_JAR" "$main_class"
  local with_agent=$?
  set -e
  if [ "$with_agent" -ne 0 ]; then
    echo "FAIL: $title expected exit 0 with the agent, got $with_agent" >&2
    rm -rf "$stage"
    exit 1
  fi

  echo
  echo "--- without the agent: the same program, the service runs, the plugin does not ---"
  set +e
  java -cp "$API_JAR:$SAMPLE_JAR" "$main_class"
  local without_agent=$?
  set -e
  rm -rf "$stage"
  if [ "$without_agent" -ne 2 ]; then
    echo "FAIL: $title expected exit 2 without the agent, got $without_agent" >&2
    exit 1
  fi
}

run_scenario "ticket redaction" "$REDACTION_JAR" com.example.support.RedactionDemo
run_scenario "gateway resilience" "$RESILIENCE_JAR" com.example.gateway.ResilienceDemo

echo
echo "scenario demos ok: both exit 0 with the agent and 2 without it"
