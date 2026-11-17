#!/usr/bin/env python3
"""Save, prepare, and restore host settings for one locked Hetzner benchmark run."""

import json
import subprocess
import sys
from pathlib import Path

SERVICES = ("apt-daily.timer", "apt-daily-upgrade.timer", "unattended-upgrades.service")


def service_state(name: str) -> str:
    return subprocess.run(
        ("systemctl", "is-active", name), capture_output=True, text=True, check=False
    ).stdout.strip()


def prepare(path: Path) -> None:
    if path.exists():
        raise SystemExit(f"Host state file already exists: {path}")
    governors = {
        str(p): p.read_text().strip()
        for p in sorted(Path("/sys/devices/system/cpu/cpufreq").glob("policy*/scaling_governor"))
    }
    if not governors:
        raise SystemExit("No CPU frequency governors found")
    services = {name: service_state(name) for name in SERVICES}
    path.write_text(json.dumps({"governors": governors, "services": services}, indent=2) + "\n")
    for name, state in services.items():
        if state == "active":
            subprocess.run(("systemctl", "stop", name), check=True)
    for name in governors:
        Path(name).write_text("performance")
    if any(Path(name).read_text().strip() != "performance" for name in governors):
        raise RuntimeError("Could not set every CPU governor to performance")


def restore(path: Path) -> None:
    state = json.loads(path.read_text())
    for name, governor in state["governors"].items():
        Path(name).write_text(governor)
    for name, previous_state in state["services"].items():
        action = "start" if previous_state == "active" else "stop"
        subprocess.run(("systemctl", action, name), check=True)
    if any(Path(name).read_text().strip() != governor for name, governor in state["governors"].items()):
        raise RuntimeError("CPU governor restoration verification failed")
    if any(service_state(name) != previous_state for name, previous_state in state["services"].items()):
        raise RuntimeError("Service restoration verification failed")
    path.with_name("host-restored-state.json").write_text(json.dumps(state, indent=2) + "\n")


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] not in ("prepare", "restore"):
        raise SystemExit("Usage: host-settings.py prepare|restore STATE_FILE")
    (prepare if sys.argv[1] == "prepare" else restore)(Path(sys.argv[2]))
