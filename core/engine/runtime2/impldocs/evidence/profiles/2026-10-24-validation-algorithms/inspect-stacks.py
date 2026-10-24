"""Python 3: inspect-stacks.py BEFORE_SAMPLES.json AFTER_SAMPLES.json OUTPUT.json.

Consumes phase-tagged, leaf-first stacks from analyze.py; writes validation-only counts.
Inclusive groups overlap. Each sample is counted once per group, including recursion.
"""

import collections
import json
import sys
from pathlib import Path


PHASES = ('correctResolution', 'application identity oracle', 'from-field binding oracle')
GROUPS = {
    'demand closure walk': lambda m: '.ResolverDemandValidationLogic.objectIsClosedUnderResolverDemand' in m,
    'value conformance walk (includes demand after fusion)': lambda m: '.ResolverConformanceLogic.' in m,
    'resolver replay': lambda m: '.ResolverReplayLogic.' in m,
    'occurrence walk including callbacks': lambda m: '.RegisteredResolverOccurrenceKt' in m,
    'identity reconstruction': lambda m: '.ResolverWitnessOracleKt.registeredResolverApplicationIdentityCounts' in m,
    'selection presence': lambda m: '.ConformsToSelectionsKt.' in m,
    'checked selections': lambda m: '.ConformsToCheckedSelectionsKt.' in m,
    'input materialization': lambda m: '.MaterializationLogic.' in m or m.endswith('.MaterializeResultKt.materializeResult'),
    'input fingerprints': lambda m: m.endswith('.ResolutionWitnessKt.resolutionFingerprint'),
    'variable enumeration': lambda m: any(m.endswith('.' + name) for name in (
        'ArgumentExpressionsKt.variables', 'ArgumentExpressionsKt.usedVariables', 'SelectionKt.usedVariables',
    )),
    'stored-key lookup': lambda m: m.endswith('.StoredObjectKeyKt.findStoredKey'),
    'materialized equality': lambda m: m.endswith('.ResolverInputReplayKt.sameMaterializedValueAs'),
    'root-reference witness lookup': lambda m: '.RootFieldReferenceWitness.' in m or m.endswith('.ResolverApplicationCache.rootFieldReferenceCandidates'),
}


def inspect(path):
    samples = [sample for sample in json.loads(path.read_text()) if sample['phase'] in PHASES]
    if not samples or any(not sample['frames'] for sample in samples):
        raise ValueError('Expected nonempty leaf-first validation stacks')
    inclusive = collections.Counter()
    self_counts = collections.Counter()
    for sample in samples:
        methods = [method for method, line in sample['frames']]
        inclusive.update(set(methods))
        self_counts[methods[0]] += 1
    groups = {}
    for label, matches in GROUPS.items():
        selected = [sample for sample in samples if any(matches(method) for method, line in sample['frames'])]
        callers = collections.Counter()
        leaves = collections.Counter()
        self_count = 0
        middle_count = 0
        for sample in selected:
            frames = sample['frames']
            indices = [i for i, (method, line) in enumerate(frames) if matches(method)]
            self_count += 0 in indices
            middle_count += any(0 < i < len(frames) - 1 for i in indices)
            outer = max(indices)
            if outer + 1 < len(frames):
                callers[str(frames[outer + 1])] += 1
            leaves[frames[0][0]] += 1
        groups[label] = {
            'samples': len(selected),
            'percent': 100 * len(selected) / len(samples),
            'self': self_count,
            'middle': middle_count,
            'by_phase': dict(collections.Counter(sample['phase'] for sample in selected)),
            'callers': callers.most_common(12),
            'leaves': leaves.most_common(12),
        }
    return {
        'source': str(path),
        'validation_samples': len(samples),
        'samples_by_phase': dict(collections.Counter(sample['phase'] for sample in samples)),
        'max_stack_depth': max(len(sample['frames']) for sample in samples),
        'samples_reaching_configured_depth_256': sum(len(sample['frames']) >= 256 for sample in samples),
        'self': self_counts.most_common(40),
        'inclusive_runtime2': [(method, count) for method, count in inclusive.most_common() if method.startswith('viaduct.engine.runtime2.')][:100],
        'groups': groups,
    }


if len(sys.argv) != 4:
    raise SystemExit(__doc__)

result = {name: inspect(Path(path)) for name, path in zip(('before', 'after'), sys.argv[1:3])}
Path(sys.argv[3]).write_text(json.dumps(result, indent=2) + '\n')
