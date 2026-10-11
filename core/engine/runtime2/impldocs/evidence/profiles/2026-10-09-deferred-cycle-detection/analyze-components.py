#!/usr/bin/env python3
"""Count union membership for named work families; percentages are inclusive and overlap."""
import collections
import json
import pathlib
import subprocess
import sys

recording = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2])
events = json.loads(subprocess.check_output([
    "jfr", "print", "--json", "--events", "jdk.ExecutionSample",
    "--stack-depth", "256", str(recording),
]))["recording"]["events"]
counts = collections.Counter()
for event in events:
    frames = (event["values"].get("stackTrace") or {}).get("frames", [])
    methods = {f["method"]["type"]["name"] + "." + f["method"]["name"] for f in frames}
    components = {
        "successor_any": any("resolution/SuccessorDemandKt." in m for m in methods),
        "successor_entry": any(m.endswith("SuccessorDemandKt.successorDemandFromConstructionDemand") for m in methods),
        "compact_successor": any(m.endswith("SuccessorDemandKt.compactSuccessorDemand") for m in methods),
        "construction_closure": any(m.endswith("ConstructionDemandClosureKt.closeOrchestrationConstructionDemand") for m in methods),
        "resolver_input_materialization": any("resolution/ResolverInputMaterializationLogic." in m for m in methods),
        "fixture_output_materialization": any(m.endswith("ObjectPlan.materializeObject") for m in methods),
        "resolver_relation": any(m.endswith("FieldValueResolver.evaluateRelation") for m in methods),
        "output_conformance_or_lowering": any(
            m.endswith("SchemaConformanceKt.conformsToOutputSchemaType") or
            m.endswith("SchemaConformanceKt.conformsToResolverOutputSchemaType") or
            "schema/SourceSchemaAdapter.lower" in m for m in methods
        ),
        "runtime_cycle_check": any(m.endswith("CycleCheckStateImpl.cycleCheck") for m in methods),
        "cycle_bookkeeping": any("CycleCheckStateImpl." in m for m in methods),
        "cycle_registration": any(m.endswith("CycleCheckStateImpl.registerWriter") for m in methods),
        "deferred_diagnostic_scan": any(m.endswith("DeferredCycleCheckStateImpl.findCycle") for m in methods),
        "schema_lowered_coordinate": any("LoweredSourceCoordinatesKt." in m for m in methods),
        "selection_normalization": any(m.endswith("SelectionKt.merge") or m.endswith("SelectionKt.normalizedObjectSelectionForest") for m in methods),
    }
    for name, present in components.items():
        counts[name] += int(present)
result = {
    "samples": len(events),
    "inclusive_components": {
        name: {"samples": count, "percent": 100 * count / len(events)}
        for name, count in counts.items()
    },
}
output.write_text(json.dumps(result, indent=2) + "\n")
print(recording.name, json.dumps(result))
