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
# weaver-girl-plugins is an aggregator POM now; each built-in plugin and each optional bridge
# publishes its own jar.
modules="weaver-girl-annotation weaver-girl-annotation-runtime weaver-girl-api weaver-girl-core weaver-girl-testkit weaver-girl-bridge-micrometer weaver-girl-bridge-aopalliance weaver-girl-agent"
for plugin in weaver-girl-plugins/weaver-girl-plugin-* weaver-girl-runtime-plugins/weaver-girl-runtime-*; do
  modules="$modules $plugin"
done
for module_dir in $modules; do
  # Child modules inherit their version from the parent, so read it there. The artifact name is
  # the module directory, not the path.
  version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' pom.xml | head -1)"
  [[ -n "$version" ]] || { echo "FAIL: cannot determine version for $module_dir" >&2; exit 1; }
  module="$(basename "$module_dir")"
  for suffix in .jar -sources.jar -javadoc.jar; do
    artifact="$module_dir/target/${module}-${version}${suffix}"
    [[ -f "$artifact" ]] || { echo "FAIL: missing $artifact" >&2; exit 1; }
  done
done

agent_jar="$(find weaver-girl-agent/target -maxdepth 1 -type f -name 'weaver-girl-agent-*.jar' ! -name '*sources*' ! -name '*javadoc*' | head -1)"
[[ -n "$agent_jar" ]] || { echo "FAIL: agent jar missing" >&2; exit 1; }
# Capture output first: under `pipefail`, `unzip ... | grep -q` can report SIGPIPE (141) when
# grep exits on the first match while unzip is still writing.
manifest="$(unzip -p "$agent_jar" META-INF/MANIFEST.MF)"
[[ "$manifest" == *"Premain-Class:"* ]] || { echo "FAIL: agent jar has no Premain-Class" >&2; exit 1; }
[[ "$manifest" == *"Agent-Class:"* ]] || { echo "FAIL: agent jar has no Agent-Class" >&2; exit 1; }
sha256sum "$agent_jar" > "$agent_jar.sha256"

# The agent carries no built-in plugin classes: it discovers them from the plugins/ directory
# next to the jar. A jar-only release would attach and instrument nothing, so the distribution
# archive is a hard requirement, not a convenience.
dist_zip="weaver-girl-agent/target/weaver-girl-agent-${version}-dist.zip"
[[ -f "$dist_zip" ]] || { echo "FAIL: missing distribution archive $dist_zip" >&2; exit 1; }
dist_listing="$(unzip -l "$dist_zip")"
[[ "$dist_listing" == *"weaver-girl-agent-${version}.jar"* ]] \
  || { echo "FAIL: distribution archive has no agent jar" >&2; exit 1; }
plugin_count="$(printf '%s\n' "$dist_listing" | grep -c 'plugins/weaver-girl-plugin-.*\.jar$')"
[[ "$plugin_count" -gt 0 ]] || { echo "FAIL: distribution archive has no plugin jars" >&2; exit 1; }
for required in servlet jdbc; do
  [[ "$dist_listing" == *"/plugins/weaver-girl-plugin-${required}-"* ]] \
    || { echo "FAIL: distribution archive is missing the ${required} plugin" >&2; exit 1; }
done
echo "distribution archive: $dist_zip ($plugin_count plugin jars)"
sha256sum "$dist_zip" > "$dist_zip.sha256"

echo "RELEASE CHECK PASSED"
