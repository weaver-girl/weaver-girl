#!/usr/bin/env bash
# Reproducible release gate. Every command is a hard failure.
set -euo pipefail
cd "$(dirname "$0")/.."

java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
if [[ "$java_major" != "17" ]]; then
  echo "FAIL: release builds require JDK 17 (found ${java_major:-unknown})" >&2
  exit 1
fi

echo "== clean quality-gated build =="
./mvnw -B -Pquality-gate clean verify

echo "== required publication artifacts =="
for module in weaver-girl-annotation weaver-girl-annotation-runtime weaver-girl-api weaver-girl-core weaver-girl-plugins weaver-girl-agent; do
  version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$module/pom.xml" | head -1)"
  [[ -n "$version" ]] || { echo "FAIL: cannot determine version for $module" >&2; exit 1; }
  for suffix in jar sources.jar javadoc.jar; do
    artifact="$module/target/${module}-${version}-${suffix}"
    [[ -f "$artifact" ]] || { echo "FAIL: missing $artifact" >&2; exit 1; }
  done
done

agent_jar="$(find weaver-girl-agent/target -maxdepth 1 -type f -name 'weaver-girl-agent-*.jar' ! -name '*sources*' ! -name '*javadoc*' | head -1)"
[[ -n "$agent_jar" ]] || { echo "FAIL: agent jar missing" >&2; exit 1; }
unzip -p "$agent_jar" META-INF/MANIFEST.MF | grep -q '^Premain-Class:'
unzip -p "$agent_jar" META-INF/MANIFEST.MF | grep -q '^Agent-Class:'
sha256sum "$agent_jar" > "$agent_jar.sha256"

echo "RELEASE CHECK PASSED"
