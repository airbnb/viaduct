#!/usr/bin/env python3
"""Independently verify the recorded host state after both benchmark phases."""
import fcntl
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

root = Path(__file__).resolve().parent
original = json.loads((root / "candidate/host-original-state.json").read_text())
actual = {
    "governors": {p: Path(p).read_text().strip() for p in original["governors"]},
    "services": {name: subprocess.run(["systemctl", "is-active", name], capture_output=True, text=True).stdout.strip() for name in original["services"]},
}
with open("/opt/graphql-schema-perf/benchmark.lock", "a") as lock:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    java = subprocess.run(["pgrep", "-x", "java"], capture_output=True, text=True)
    report = {
        "verified_at_utc": datetime.now(timezone.utc).isoformat(),
        "actual": actual,
        "matches_candidate_original": actual == original,
        "matches_baseline_original": actual == json.loads((root / "baseline/host-original-state.json").read_text()),
        "benchmark_lock_free": True,
        "java_processes": java.stdout.strip().splitlines(),
    }
    (root / "host-verification.json").write_text(json.dumps(report, indent=2) + "\n")
    assert report["matches_candidate_original"] and report["matches_baseline_original"]
    assert not report["java_processes"]
print("Host restoration verified; benchmark lock free; no Java process remains.")
