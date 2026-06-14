#!/usr/bin/env bash
# Release-readiness report for weaver-girl.
#
# Hard gate (must pass):
#   [1] Unit tests .............. clean test MUST be green (1376+ tests)
#
# Reported gate-status (informational — known gaps, tracked in RELEASE_READINESS.md):
#   [2] JaCoCo coverage ......... target 80% instr / 70% branch; currently NOT met
#   [3] Checkstyle .............. google_checks, maxViolations 200; whole-codebase
#                                 reformat to 2-space still pending
#   [4] SpotBugs ................. threshold High
#
# This script exits non-zero ONLY if unit tests fail. The quality-gate gaps are
# printed so they are visible — see RELEASE_READINESS.md for the path to green.
set -uo pipefail
cd "$(dirname "$0")/.."

echo "== [1] Clean build + unit tests (HARD GATE) =="
if ! ./mvnw -o clean test; then
  echo "FAIL: unit tests failed"; exit 1
fi
echo "   unit tests: GREEN"

echo ""
echo "== [2] JaCoCo coverage (target 80% instr / 70% branch) =="
./mvnw -o jacoco:report >/dev/null 2>&1 || true
for f in $(find . -path "*/target/site/jacoco/jacoco.csv" 2>/dev/null | sort); do
  mod=$(echo "$f" | sed -E 's#\./([^/]+)/.*#\1#')
  awk -F, 'NR>1 {im+=$4; ic+=$5; bm+=$6; bc+=$7} END {
    ti=ic+im; tb=bc+bm;
    if(ti>0) printf "   %-28s instr %.0f%%  branch %.0f%%\n", "'"$mod"'", 100.0*ic/ti, (tb>0?100.0*bc/tb:0)
  }' "$f"
done

echo ""
echo "== [3] Checkstyle violation count (google_checks, max 200) =="
for mod in weaver-girl-api weaver-girl-core weaver-girl-plugins weaver-girl-annotation weaver-girl-agent; do
  ./mvnw -o -pl "$mod" checkstyle:checkstyle -Dcheckstyle.failOnViolation=false -Dcheckstyle.violationSeverity=warning \
        -q >/dev/null 2>&1 || true
  xml="$mod/target/checkstyle-result.xml"
  n=$(grep -c "<error " "$xml" 2>/dev/null || echo 0)
  printf "   %-28s %s violations\n" "$mod" "$n"
done

echo ""
echo "== [4] SpotBugs (threshold High) =="
./mvnw -o spotbugs:check -q >/dev/null 2>&1 && echo "   spotbugs: GREEN" || echo "   spotbugs: violations present (see target/spotbugsXml.xml)"

echo ""
echo "RELEASE CHECK DONE — unit tests GREEN; quality-gate status reported above."
echo "Path to a green quality gate: RELEASE_READINESS.md"
