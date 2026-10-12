#!/usr/bin/env python3
"""Separate fixture construction/fingerprints from engine relation evaluation."""
import collections
import json
import pathlib
import subprocess
import sys

events = json.loads(subprocess.check_output([
    "jfr", "print", "--json", "--events", "jdk.ExecutionSample",
    "--stack-depth", "256", sys.argv[1],
]))["recording"]["events"]
counts = collections.Counter()
names = [
    "fixture_object_materialization", "generated_hash_construction", "sensitive_scalar",
    "fixture_output_fingerprinting", "argument_fingerprinting", "resolver_relation",
]
for event in events:
    frames = (event["values"].get("stackTrace") or {}).get("frames", [])
    methods = {f["method"]["type"]["name"] + "." + f["method"]["name"] for f in frames}
    components = {
        "fixture_object_materialization": any(m.endswith("ObjectPlan.materializeObject") for m in methods),
        "generated_hash_construction": any(m.endswith("RegistryGeneratorKt.generatedHashObject") for m in methods),
        "sensitive_scalar": any(m.endswith("RegistryGeneratorKt.sensitiveScalar") for m in methods),
        "fixture_output_fingerprinting": any(m.endswith("FingerprintBudget.output") for m in methods),
        "argument_fingerprinting": any(m.endswith("FingerprintBudget.arguments") for m in methods),
        "resolver_relation": any(m.endswith("FieldValueResolver.evaluateRelation") for m in methods),
    }
    counts.update(name for name, present in components.items() if present)
result = {
    "execution_samples": len(events),
    "inclusive_components": {
        name: {"samples": counts[name], "percent": 100 * counts[name] / len(events)}
        for name in names
    },
}
pathlib.Path(sys.argv[2]).write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result))
