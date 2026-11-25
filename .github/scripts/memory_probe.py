"""Throwaway CI probe: sample runner memory while tests run."""

import os
import re
import subprocess
import sys
import time

INTERVAL_SECONDS = 15
WARN_BELOW_MB = [3072, 2048, 1024, 512]


def meminfo_mb():
    values = {}
    with open("/proc/meminfo") as f:
        for line in f:
            key, rest = line.split(":", 1)
            values[key] = int(rest.split()[0]) // 1024
    return values


def pressure(resource):
    try:
        with open(f"/proc/pressure/{resource}") as f:
            some = f.readline().split()
        return next(p.split("=")[1] for p in some if p.startswith("avg10="))
    except OSError:
        return "n/a"


def label(args):
    tmpdir = re.search(r"worker\.tmpdir=(\S+)", args)
    if tmpdir:
        path = tmpdir.group(1).replace(os.environ.get("GITHUB_WORKSPACE", ""), "").strip("/")
        return f"test-worker {path.split('/build/')[0]} {path.split('/build/tmp/')[-1]}"
    for marker in ("GradleDaemon", "GradleWrapperMain", "KotlinCompileDaemon", "GradleWorkerMain"):
        if marker in args:
            return marker
    return os.path.basename(args.split(" ", 1)[0])


def load_average():
    with open("/proc/loadavg") as f:
        return f.read().split()[0]


def top_processes(count=8):
    out = subprocess.run(["ps", "-eo", "rss=,args="], capture_output=True, text=True).stdout
    rows = []
    java_count = 0
    for line in out.splitlines():
        rss, _, args = line.strip().partition(" ")
        if rss.isdigit():
            java_count += bool(re.match(r"\S*java\s", args))
            xmx = re.search(r"-Xmx(\S+)", args)
            rows.append((int(rss) // 1024, xmx.group(1) if xmx else "-", label(args)))
    rows.sort(reverse=True)
    return rows[:count], java_count


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    start = time.time()
    warned = set()
    min_avail = None
    with open(os.path.join(out_dir, "samples.txt"), "a") as samples:
        while True:
            mem = meminfo_mb()
            avail = mem["MemAvailable"]
            swap_used = mem.get("SwapTotal", 0) - mem.get("SwapFree", 0)
            minutes = (time.time() - start) / 60
            top, java_count = top_processes()
            load = load_average()
            header = (
                f"{time.strftime('%H:%M:%S', time.gmtime())} +{minutes:5.1f}m avail={avail}MB "
                f"total={mem['MemTotal']}MB swap_used={swap_used}MB load1={load} "
                f"psi_mem10={pressure('memory')} psi_cpu10={pressure('cpu')} jvms={java_count}"
            )
            samples.write(header + "\n")
            for rss, xmx, name in top:
                samples.write(f"    {rss:6}MB xmx={xmx:5} {name}\n")
            samples.flush()
            if min_avail is None or avail < min_avail:
                min_avail = avail
                with open(os.path.join(out_dir, "min.txt"), "w") as f:
                    f.write(header + "\n" + "\n".join(f"    {r}MB xmx={x} {n}" for r, x, n in top) + "\n")
            for threshold in WARN_BELOW_MB:
                if avail < threshold and threshold not in warned:
                    warned.add(threshold)
                    biggest = "; ".join(f"{n} {r}MB" for r, _, n in top[:3])
                    print(f"::warning title=memprobe below {threshold}MB::{header} | {biggest}", flush=True)
            time.sleep(INTERVAL_SECONDS)


if __name__ == "__main__":
    main(sys.argv[1])
