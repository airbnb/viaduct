#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "Usage: $0 JMH_JAR RESULT_DIRECTORY [eager|backed|baseline|unified]" >&2
    exit 2
fi

benchmark_method=".*"
case "${3:-eager}" in
    eager) benchmark_class=NormalizedDocumentSelectionBenchmark; cases=6; deadline=70m ;;
    backed) benchmark_class=NormalizedDocumentBackedForestBenchmark; cases=4; deadline=50m ;;
    baseline) benchmark_class=NormalizedDocumentBackedForestBenchmark; benchmark_method=".consumeAst"; cases=1; deadline=15m ;;
    unified) benchmark_class=NormalizedDocumentBackedForestBenchmark; benchmark_method=".consumeNormalizedShared"; cases=1; deadline=15m ;;
    *) echo "Unknown benchmark suite: $3" >&2; exit 2 ;;
esac
iteration_seconds=$((cases * 2 * 240))
jar=$(realpath "$1")
result_dir=$2
mkdir -p "$result_dir"
result_dir=$(realpath "$result_dir")
script_dir=$(dirname "$(realpath "$0")")
host_state="$result_dir/host-original-state.json"

exec 9>/opt/graphql-schema-perf/benchmark.lock
if ! flock -n 9; then
    echo "Hetzner benchmark lock is held; no JMH process was started" >&2
    exit 75
fi

restore_host() {
    rc=$?
    trap - EXIT
    if [[ -f "$host_state" ]]; then
        if ! python3 "$script_dir/selection-forest-benchmark-host-settings.py" restore "$host_state"; then
            echo "Host setting restoration failed; inspect $host_state" >&2
            rc=1
        fi
    fi
    exit "$rc"
}
trap restore_host EXIT

python3 "$script_dir/selection-forest-benchmark-host-settings.py" prepare "$host_state"
{
    date -u '+Started: %Y-%m-%dT%H:%M:%SZ'
    java -version 2>&1
    sha256sum "$jar"
    uname -a
    echo 'GraphQL Java PR 4562 head: 69c7bfbef920e5b95b7c295b60ef0652523eef3d'
    echo "Suite: $benchmark_class"
    echo "Cases: $cases methods x 2 forks"
    echo 'Per fork: 2 warmup x 30s + 3 measurement x 60s = 240s'
    echo "Scheduled JMH iteration time: ${iteration_seconds}s ($((iteration_seconds / 60))m)"
    echo "Wall-clock deadline for JMH, including fork setup: $deadline plus 30s termination grace"
} > "$result_dir/metadata.txt"

timeout --signal=TERM --kill-after=30s "$deadline" \
    java -cp "$jar" org.openjdk.jmh.Main \
        "viaduct.engine.runtime2.schema.${benchmark_class}${benchmark_method}" \
        -t 1 -prof gc -rf json -rff "$result_dir/results.json" \
    2>&1 | tee "$result_dir/jmh.log"

python3 - "$result_dir/results.json" "$cases" <<'PY'
import json
import sys

results = json.load(open(sys.argv[1]))
expected = int(sys.argv[2])
if len(results) != expected:
    raise SystemExit(f"Expected {expected} benchmark results, found {len(results)}")
print(f"Verified {expected} benchmark results")
PY
