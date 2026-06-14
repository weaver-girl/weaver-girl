#!/usr/bin/env bash
#
# verify-benchmarks.sh — regression guard for the weaver-girl JMH baseline.
#
# Runs the JMH bench (in-process, fast) and checks that every benchmark stays
# within REGRESSION_FACTOR (default 1.5x) of the baseline scores recorded in
# docs/benchmarks/baseline-v1.md.
#
# Usage:
#   scripts/verify-benchmarks.sh                 # fast:  -Djmh.wi=2 -Djmh.i=3
#   JMH_WI=3 JMH_I=5 scripts/verify-benchmarks.sh # more precise
#   SKIP_BENCH=1 scripts/verify-benchmarks.sh    # parse an existing /tmp/bench-out.txt
#
# Exit codes:
#   0  all benchmarks within budget
#   1  one or more benchmarks regressed beyond REGRESSION_FACTOR
#   2  could not produce/parse benchmark output
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REGRESSION_FACTOR="${REGRESSION_FACTOR:-1.5}"
BENCH_OUT="${BENCH_OUT:-/tmp/bench-out.txt}"
JMH_WI="${JMH_WI:-2}"
JMH_I="${JMH_I:-3}"

# Baseline scores (ns/op) from docs/benchmarks/baseline-v1.md.
declare -A BASELINE=(
  ["InterceptorBenchmark.baseline_noRegistry"]=5.632
  ["InterceptorBenchmark.pointcutMatching_hit"]=74.243
  ["InterceptorBenchmark.pointcutMatching_miss"]=72.195
  ["InterceptorBenchmark.registryLookup_emptyRegistry"]=10.699
  ["InterceptorBenchmark.registryLookup_withInterceptor"]=20.847
  ["MatcherBenchmark.classMatcher_exactMatch"]=1.181
  ["MatcherBenchmark.classMatcher_exactMiss"]=1.478
  ["MatcherBenchmark.classMatcher_patternMatch"]=133.748
  ["MatcherBenchmark.classMatcher_patternMiss"]=29.722
  ["MatcherBenchmark.methodMatcher_exactMatch"]=1.176
  ["MatcherBenchmark.methodMatcher_patternMatch"]=44.508
  ["CoreComponentBenchmark.circuitBreaker_recordSuccess"]=24.416
  ["CoreComponentBenchmark.circuitBreaker_shouldInvoke_fresh"]=25.028
  ["CoreComponentBenchmark.pool_acquireRelease"]=759.948
  ["CoreComponentBenchmark.sampling_shouldSample_rate1"]=2.740
  ["CoreComponentBenchmark.sampling_shouldSample_rate10"]=7.271
)

if [ "${SKIP_BENCH:-0}" != "1" ]; then
  echo "==> Running JMH bench (wi=$JMH_WI i=$JMH_I forks=0)..."
  ./mvnw -Pbench -DskipTests -Djmh.wi="$JMH_WI" -Djmh.i="$JMH_I" -Djmh.f=0 \
      integration-test -pl weaver-girl-core > "$BENCH_OUT" 2>&1 || {
    echo "ERROR: benchmark run failed; see $BENCH_OUT" >&2
    exit 2
  }
else
  echo "==> SKIP_BENCH=1 — parsing existing $BENCH_OUT"
fi

if [ ! -f "$BENCH_OUT" ]; then
  echo "ERROR: $BENCH_OUT not found" >&2
  exit 2
fi

# Parse JMH "Result" summary blocks: each "Result \"<full.name>\":" line is paired
# with the immediately-following score line "  24.416 ±(99.9%) 2.299 ns/op [Average]".
# Emit "<full-name>\t<score>" pairs (awk avoids grep -A1's "--" separators).
mapfile -t PAIRS < <(
  awk '
    /^Result "/ {
      name = $0; sub(/^Result "/, "", name); sub(/":.*$/, "", name)
      pending = name
    }
    pending && /^  [0-9]/ {
      print pending "\t" $1
      pending = ""
    }
  ' "$BENCH_OUT"
)

failures=0
checked=0
printf "%-58s %12s %12s %8s  %s\n" "BENCHMARK" "BASELINE" "MEASURED" "RATIO" "STATUS"
printf '%0.s-' {1..100}; echo

for pair in "${PAIRS[@]}"; do
  full="${pair%%$'\t'*}"
  score="${pair##*$'\t'}"
  # full looks like: com.github...InterceptorBenchmark.baseline_noRegistry
  short="$(printf '%s' "$full" | sed -E 's/.*\.(InterceptorBenchmark|MatcherBenchmark|CoreComponentBenchmark)\./\1./')"
  [ -z "$short" ] || [ -z "$score" ] && continue
  base="${BASELINE[$short]:-}"
  if [ -z "$base" ]; then
    printf "%-58s %12s %12s %8s  %s\n" "$short" "(none)" "$score" "-" "SKIP(no baseline)"
    continue
  fi
  # Compare using awk for floats.
  ratio="$(awk -v s="$score" -v b="$base" 'BEGIN{ printf "%.3f", s/b }')"
  over="$(awk -v r="$ratio" -v f="$REGRESSION_FACTOR" 'BEGIN{ print (r>f) ? 1 : 0 }')"
  checked=$((checked+1))
  if [ "$over" = "1" ]; then
    printf "%-58s %12s %12s %8s  %s\n" "$short" "$base" "$score" "$ratio" "FAIL (>${REGRESSION_FACTOR}x)"
    failures=$((failures+1))
  else
    printf "%-58s %12s %12s %8s  %s\n" "$short" "$base" "$score" "$ratio" "ok"
  fi
done

echo
echo "Checked: $checked   Failures: $failures   (budget: ${REGRESSION_FACTOR}x of baseline)"
if [ "$failures" -gt 0 ]; then
  echo "RESULT: REGRESSION DETECTED"
  exit 1
fi
echo "RESULT: within budget"
exit 0
