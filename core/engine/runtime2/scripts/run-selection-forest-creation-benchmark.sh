#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "Usage: $0 JMH_JAR RESULT_DIRECTORY [FIXTURE]" >&2
    exit 2
fi

fixture_args=()
if [[ $# -eq 3 ]]; then
    case "$3" in
        schema2-small|schema4-small|schema4-full) ;;
        *) echo "Unknown fixture: $3" >&2; exit 2 ;;
    esac
    fixture_args=(-p "fixture=$3")
    fixture_count=1
    scheduled_minutes=32
    deadline=50m
else
    fixture_count=3
    scheduled_minutes=96
    deadline=110m
fi

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
    echo "Cases: 4 methods x $fixture_count fixtures x 2 forks"
    echo 'Per fork: 2 warmup x 30s + 3 measurement x 60s = 240s'
    echo "Scheduled JMH iteration time: ${scheduled_minutes}m"
    echo "Wall-clock deadline for JMH, including fork setup: $deadline plus 30s termination grace"
} > "$result_dir/metadata.txt"

timeout --signal=TERM --kill-after=30s "$deadline" \
    java -cp "$jar" org.openjdk.jmh.Main \
        'viaduct.engine.runtime2.schema.SelectionForestCreationBenchmark.*' \
        "${fixture_args[@]}" \
        -t 1 -prof gc -rf json -rff "$result_dir/results.json" \
    2>&1 | tee "$result_dir/jmh.log"

python3 - "$result_dir/results.json" "$fixture_count" <<'PY'
import json
import sys

results = json.load(open(sys.argv[1]))
expected = 4 * int(sys.argv[2])
if len(results) != expected:
    raise SystemExit(f"Expected {expected} benchmark results, found {len(results)}")
print(f"Verified {expected} benchmark results")
PY
