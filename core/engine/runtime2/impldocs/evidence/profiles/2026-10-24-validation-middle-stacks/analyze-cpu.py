import bisect
import collections
import datetime
import json
import statistics
import sys
from pathlib import Path

source = Path(sys.argv[1])
prefix = Path(sys.argv[2])
events = json.load(source.open())["recording"]["events"]
def ts(value):
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()

phases = []
durations = collections.defaultdict(list)
for event in events:
    if event["type"] == "qplan.PropertyTestPhase":
        value = event["values"]
        start = ts(value["startTime"])
        duration = float(value["duration"][2:-1])
        phases.append((start, start + duration, value["phase"]))
        durations[value["phase"]].append(1000 * duration)
phases.sort()
starts = [p[0] for p in phases]
samples = []
all_threads = collections.Counter()
for event in events:
    if event["type"] not in ("jdk.ExecutionSample", "jdk.NativeMethodSample"):
        continue
    value = event["values"]
    thread = value.get("sampledThread") or {}
    name = thread.get("javaName") or thread.get("osName") or ""
    all_threads[name] += 1
    stack = value.get("stackTrace") or {}
    frames = [(f["method"]["type"]["name"].replace("/", ".") + "." + f["method"]["name"], f.get("lineNumber", -1)) for f in stack.get("frames", [])]
    if not any(m.startswith("viaduct.engine.runtime2.") for m, line in frames):
        continue
    time = ts(value["startTime"])
    i = bisect.bisect_right(starts, time) - 1
    phase = phases[i][2] if i >= 0 and time <= phases[i][1] else "outside phases"
    samples.append(dict(phase=phase, time=value["startTime"], thread=name, truncated=stack.get("truncated", False), frames=frames))
del events
validation_phases = ("correctResolution", "application identity oracle", "from-field binding oracle")
stats = {}
for phase in [*durations, "outside phases", "ALL VALIDATION"]:
    subset = [s for s in samples if s["phase"] in validation_phases] if phase == "ALL VALIDATION" else [s for s in samples if s["phase"] == phase]
    inclusive = collections.Counter()
    flat = collections.Counter()
    middle = collections.Counter()
    depth = collections.defaultdict(list)
    for s in subset:
        methods = [m for m, line in s["frames"]]
        inclusive.update(set(methods))
        middle.update(set(methods[1:-1]))
        flat[methods[0]] += 1
        seen = set()
        for i, m in enumerate(methods):
            if m not in seen:
                depth[m].append(i)
                seen.add(m)
    stats[phase] = dict(samples=len(subset), threads=dict(collections.Counter(s["thread"] for s in subset)), truncated=sum(s["truncated"] for s in subset), max_depth=max((len(s["frames"]) for s in subset), default=0), methods=[dict(method=m, inclusive=count, self=flat[m], middle=middle[m], median_depth=statistics.median(depth[m]), max_depth=max(depth[m])) for m, count in inclusive.most_common()])

result = dict(source=str(source), stack_depth=256, phase_durations_ms={p:dict(n=len(v), mean=statistics.mean(v), median=statistics.median(v), min=min(v), max=max(v), iterations=v) for p, v in durations.items()}, all_sample_threads=dict(all_threads), cpu=stats)
prefix.with_suffix(".json").write_text(json.dumps(result, indent=2))
prefix.with_name(prefix.name + "-samples.json").write_text(json.dumps(samples))
with prefix.with_suffix(".txt").open("w") as out:
    for p, v in result["phase_durations_ms"].items():
        print(p, v["n"], round(v["mean"], 3), "ms/case", file=out)
    for p in ("ALL VALIDATION", *validation_phases):
        r = stats[p]
        print("\nPHASE", p, "samples", r["samples"], "truncated", r["truncated"], "max depth", r["max_depth"], file=out)
        print("inclusive % self middle median-depth method", file=out)
        for m in r["methods"]:
            if m["method"].startswith("viaduct.engine.runtime2.") and m["inclusive"] >= 4:
                print(m["inclusive"], round(100 * m["inclusive"] / r["samples"], 2), m["self"], m["middle"], m["median_depth"], m["method"], file=out)
print(prefix.with_suffix(".txt"))
