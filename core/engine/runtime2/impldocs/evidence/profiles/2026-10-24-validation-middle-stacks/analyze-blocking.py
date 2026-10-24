import collections
import json
import sys
from pathlib import Path

prefix = Path(sys.argv[1])
samples = json.loads(prefix.with_name(prefix.name + '-samples.json').read_text())
samples = [s for s in samples if s['phase'] in ('correctResolution', 'application identity oracle', 'from-field binding oracle')]
plumbing = []
for sample in samples:
    frames = sample['frames']
    boundary = next((i for i, (method, line) in enumerate(frames) if method == 'kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking'), None)
    if boundary is None or any(method.startswith('viaduct.engine.runtime2.') for method, line in frames[:boundary]):
        continue
    caller = next(((method, line) for method, line in frames[boundary:] if method.startswith('viaduct.engine.runtime2.')), None)
    if caller is None or caller[0].startswith('viaduct.engine.runtime2.benchmark.PropertyTestBenchmarkSupport'):
        continue
    plumbing.append((sample, caller))

result = dict(
    samples=len(plumbing),
    denominator=len(samples),
    by_phase=dict(collections.Counter(s['phase'] for s, caller in plumbing)),
    callers=collections.Counter(str(caller) for s, caller in plumbing).most_common(),
    leaves=collections.Counter(s['frames'][0][0] for s, caller in plumbing).most_common(),
    examples=[s for s, caller in plumbing[:12]],
    method='Find the innermost BuildersKt__BuildersKt.runBlocking implementation frame. Count only when no Runtime2 frame occurs above it and the nearest Runtime2 caller below it is not the outer benchmark. This isolates nested blocking bridge plumbing from sampled Runtime2 bodies; samples are counted once.',
)
prefix.with_name(prefix.name + '-blocking.json').write_text(json.dumps(result, indent=2))
print(json.dumps({k: v for k, v in result.items() if k != 'examples'}, indent=2))
