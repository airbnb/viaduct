#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
phase=${1:?baseline or candidate required}
case "$phase" in baseline|candidate) ;; *) exit 64 ;; esac
exec 9>/opt/graphql-schema-perf/benchmark.lock
flock -n 9 || exit 75
mkdir "$phase"
restore() {
    rc=$?
    trap - EXIT
    if [[ -f "$phase/host-original-state.json" ]]; then
        python3 host-settings.py restore "$phase/host-original-state.json" || rc=1
    fi
    date -u '+Finished: %Y-%m-%dT%H:%M:%SZ' >> "$phase/metadata.txt"
    printf '%s\n' "$rc" > "$phase/exit-code"
    exit "$rc"
}
trap restore EXIT
python3 host-settings.py prepare "$phase/host-original-state.json"
{
    date -u '+Started: %Y-%m-%dT%H:%M:%SZ'
    java -version 2>&1
    uname -a
    lscpu | head -n 20
    sha256sum "$phase.jar"
} > "$phase/metadata.txt"
timeout --signal=TERM --kill-after=30s 15m \
    java -cp "$phase.jar" org.openjdk.jmh.Main \
    'viaduct.engine.runtime2.schema.SelectionForestCollectionBenchmark.mergeAndCollect' \
    -f 2 -t 1 -wi 2 -w 30s -i 3 -r 60s -prof gc -rf json -rff "$phase/results.json" \
    > "$phase/jmh.log" 2>&1
python3 - "$phase/results.json" <<'CHECK'
import json, sys
results = json.load(open(sys.argv[1]))
assert len(results) == 1 and results[0]['primaryMetric']['score'] > 0
CHECK
