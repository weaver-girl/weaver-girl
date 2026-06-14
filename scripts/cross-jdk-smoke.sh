#!/usr/bin/env bash
#
# Cross-JDK agent-attach smoke test for weaver-girl.
#
# Builds the packaged agent + sample ONCE (compiler targets Java 1.8 bytecode, so the
# artifacts run on JDK 8/11/17/21), then attaches the agent to the sample app under
# EACH JDK provided and asserts the agent's /health, /ready and /metrics endpoints
# respond. This is the regression net for JVM-version compatibility and classloader
# isolation — it exercises the real `-javaagent` path the in-process unit suite cannot.
#
# Usage:
#   scripts/cross-jdk-smoke.sh                       # every JDK under /usr/lib/jvm/*
#   scripts/cross-jdk-smoke.sh /path/to/java ...     # explicit JDK java binaries
#   SKIP_BUILD=1 scripts/cross-jdk-smoke.sh          # reuse an already-built agent+sample
#   MVNW=/tmp/maven/bin/mvn scripts/cross-jdk-smoke.sh
#
set -euo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"

MVNW="${MVNW:-./mvnw}"

# --- Discover JDKs ---------------------------------------------------------
if [ "$#" -gt 0 ]; then
  JDKS=("$@")
else
  JDKS=()
  for j in /usr/lib/jvm/*/bin/java; do
    [ -x "$j" ] && JDKS+=("$j")
  done
fi
if [ "${#JDKS[@]}" -eq 0 ]; then
  echo "No JDKs found. Pass java binaries as args." >&2
  exit 1
fi
echo "Testing ${#JDKS[@]} JDK(s):"
for j in "${JDKS[@]}"; do printf '  %s\n' "$j"; done

# --- Build once (1.8 bytecode → runs on 8/11/17/21) ------------------------
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo ""
  echo "== Building packaged agent + sample =="
  "$MVNW" -B -pl weaver-girl-agent -am package -DskipTests -q
  "$MVNW" -B -pl weaver-girl-sample -am test-compile -DskipTests -q
  "$MVNW" -B -pl weaver-girl-sample dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/sample-libs -q
fi

AGENT_JAR=$(ls weaver-girl-agent/target/weaver-girl-agent-*.jar | grep -vE 'sources|javadoc' | head -1)
test -n "$AGENT_JAR" || { echo "agent jar missing — build first" >&2; exit 1; }
CP="weaver-girl-sample/target/classes:weaver-girl-sample/target/sample-libs/*"

# --- Per-JDK attach --------------------------------------------------------
PORT_BASE=28000   # high base to avoid shadowing local services
fail=0
idx=0
for JAVA in "${JDKS[@]}"; do
  idx=$((idx + 1))
  SPORT=$((PORT_BASE + idx * 10))      # sample app http port
  MPORT=$((PORT_BASE + idx * 10 + 1))  # agent prometheus metrics port
  HPORT=$((PORT_BASE + idx * 10 + 2))  # agent health/ready port
  JV=$("$JAVA" -version 2>&1 | head -1)
  LOG="/tmp/wg-smoke-jdk-$idx.log"
  echo ""
  echo "=== JDK $JV ==="
  echo "    $JAVA"

  "$JAVA" -Dsample.port="$SPORT" \
          -javaagent:"$AGENT_JAR"=metricsPort="$MPORT",healthPort="$HPORT" \
          -cp "$CP" \
          com.github.cc11001100.weavergirl.sample.app.SampleApplication \
          >"$LOG" 2>&1 &
  PID=$!
  ok=1

  # Bounded wait for readiness (or early exit).
  for _ in $(seq 1 60); do
    if curl -sf "http://localhost:$HPORT/ready" >/dev/null 2>&1; then break; fi
    if ! kill -0 "$PID" 2>/dev/null; then
      echo "FAIL: process exited before becoming ready. Tail of $LOG:" >&2
      tail -30 "$LOG" >&2 || true
      ok=0
      break
    fi
    sleep 1
  done

  if [ "$ok" -eq 1 ]; then
    curl -sf "http://localhost:$HPORT/health" | grep -q '"status":"UP"'    || { echo "FAIL: /health";  ok=0; }
    curl -sf "http://localhost:$HPORT/ready"  | grep -q '"status":"READY"' || { echo "FAIL: /ready";   ok=0; }
    curl -sf "http://localhost:$HPORT/stats"  | grep -q 'interceptorInvocationCount' || { echo "FAIL: /stats"; ok=0; }
    curl -sf "http://localhost:$MPORT/metrics" | grep -q 'weavergirl'      || { echo "FAIL: /metrics"; ok=0; }

    # POSITIVE interception assertion: the agent's /ready fires during premain,
    # BEFORE the sample binds Jetty, so poll the app port until it answers, then
    # drive servlet traffic and confirm the agent actually intercepted it.
    # weavergirl_operation_duration_ms_count{plugin="servlet"} > 0 is the signal:
    # the servlet plugin publishes a "request" event on every service() call. A
    # classloader-split regression (advice -> null registry) makes this stay absent.
    if [ "$ok" -eq 1 ]; then
      for _ in $(seq 1 60); do
        code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$SPORT/health" 2>/dev/null || true)
        { [ "$code" != "000" ] && [ -n "$code" ]; } && break
        if ! kill -0 "$PID" 2>/dev/null; then echo "FAIL: app exited before Jetty bound" >&2; ok=0; break; fi
        sleep 1
      done
    fi
    if [ "$ok" -eq 1 ]; then
      for ep in /users /health /users; do
        curl -s -o /dev/null "http://127.0.0.1:$SPORT$ep" >/dev/null 2>&1 || true
      done
      sleep 2
      curl -sf "http://localhost:$MPORT/metrics" \
        | grep -qE '^weavergirl_operation_duration_ms_count\{plugin="servlet"\} [1-9]' \
        || { echo "FAIL: no servlet interception signal (interception broken?)"; ok=0; }
    fi
  fi

  kill "$PID" 2>/dev/null || true
  wait "$PID" 2>/dev/null || true

  if [ "$ok" -eq 1 ]; then
    echo "PASS"
  else
    echo "FAIL on $JV (full log: $LOG)" >&2
    fail=1
  fi
done

echo ""
if [ "$fail" -eq 0 ]; then
  echo "CROSS-JDK SMOKE PASSED on ${#JDKS[@]} JDK(s)"
  exit 0
fi
echo "CROSS-JDK SMOKE FAILED on at least one JDK" >&2
exit 1
