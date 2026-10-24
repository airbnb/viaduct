import collections
import json
from pathlib import Path

base = Path('/tmp/runtime2-validation-20261024')
samples = json.loads((base / 'middle-stack-analysis-samples.json').read_text())
phases = ('correctResolution', 'application identity oracle', 'from-field binding oracle')
samples = [s for s in samples if s['phase'] in phases]
groups = {
    'input materialization': lambda m: '.MaterializationLogic.' in m or m.endswith('.MaterializeResultKt.materializeResult'),
    'selection conformance': lambda m: '.ConformsToSelectionsKt.' in m,
    'generated hash object': lambda m: m.endswith('.RegistryGeneratorKt.generatedHashObject'),
    'fixture object construction': lambda m: m.endswith('.ObjectConstructionKt.objectOf'),
    'variable enumeration': lambda m: any(m.endswith('.' + x) for x in ('ArgumentExpressionsKt.variables', 'ArgumentExpressionsKt.usedVariables', 'SelectionKt.usedVariables')),
    'stored-key lookup': lambda m: m.endswith('.StoredObjectKeyKt.findStoredKey'),
    'materialized value equality': lambda m: m.endswith('.ResolverInputReplayKt.sameMaterializedValueAs'),
    'input fingerprints': lambda m: m.endswith('.ResolutionWitnessKt.resolutionFingerprint'),
    'resolver invocation lookup': lambda m: m.endswith('.CorrectnessResolverObserver.resolverInvocations'),
}
result = {'denominator': len(samples), 'groups': {}, 'overlaps': {}}
with (base / 'middle-stack-helpers.txt').open('w') as out:
    for label, matches in groups.items():
        selected = [s for s in samples if any(matches(m) for m, line in s['frames'])]
        leaves = collections.Counter(s['frames'][0][0] for s in selected)
        callers = collections.Counter()
        descendants = collections.Counter()
        sites = collections.Counter()
        stacks = collections.Counter()
        self_count = 0
        middle_count = 0
        for s in selected:
            fs = s['frames']
            indices = [i for i, (m, line) in enumerate(fs) if matches(m)]
            self_count += 0 in indices
            middle_count += any(0 < i < len(fs) - 1 for i in indices)
            outer = max(indices)
            inner = min(indices)
            if outer + 1 < len(fs):
                callers[str(fs[outer + 1])] += 1
            descendants.update(set(m for m, line in fs[:outer]))
            sites[str(fs[inner])] += 1
            stacks[';'.join(m + ':' + str(line) for m, line in fs[:outer + 5])] += 1
        by_phase = {p: sum(s['phase'] == p for s in selected) for p in phases}
        record = dict(samples=len(selected), self=self_count, middle=middle_count, by_phase=by_phase, leaves=leaves.most_common(), callers=callers.most_common(), descendants=descendants.most_common(), sites=sites.most_common(), stacks=stacks.most_common(12))
        result['groups'][label] = record
        print('\nGROUP', label, len(selected), round(100 * len(selected) / len(samples), 2), 'self', self_count, 'middle', middle_count, 'phases', by_phase, file=out)
        print('CALLERS', file=out)
        for m, n in callers.most_common(10): print(n, m, file=out)
        print('LEAVES', file=out)
        for m, n in leaves.most_common(12): print(n, m, file=out)
        print('RUNTIME2 DESCENDANTS', file=out)
        for m, n in [(m, n) for m, n in descendants.most_common() if m.startswith('viaduct.engine.runtime2.')][:18]: print(n, m, file=out)
    for label, matches in groups.items():
        result['overlaps'][label] = {other: sum(any(matches(m) for m, line in s['frames']) and any(predicate(m) for m, line in s['frames']) for s in samples) for other, predicate in groups.items()}
(base / 'middle-stack-helpers.json').write_text(json.dumps(result, indent=2))
print(base / 'middle-stack-helpers.txt')
