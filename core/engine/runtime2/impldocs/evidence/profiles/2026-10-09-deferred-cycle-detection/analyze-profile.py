#!/usr/bin/env python3
"""Derive inclusive CPU counts, phase durations, and startup-filtered allocation sites."""
import collections
import json
import pathlib
import subprocess
import sys

recording = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2])
output.mkdir(parents=True, exist_ok=True)

def events(kinds):
    command = ['jfr', 'print', '--json', '--events', kinds, '--stack-depth', '128', str(recording)]
    return json.loads(subprocess.check_output(command))['recording']['events']

def method(frame):
    return frame['method']['type']['name'] + '.' + frame['method']['name']

samples = events('jdk.ExecutionSample')
counts = collections.Counter()
for event in samples:
    frames = (event['values'].get('stackTrace') or {}).get('frames', [])
    counts.update({method(frame) for frame in frames})
with (output / 'inclusive-cpu.tsv').open('w') as stream:
    stream.write(f'# {len(samples)} execution samples; inclusive counts overlap\n')
    stream.write('samples\tpercent\tmethod\n')
    for name, count in counts.most_common():
        stream.write(f'{count}\t{100*count/len(samples):.2f}\t{name}\n')

seen = set()
excluded = []
sites = collections.Counter()
allocation_samples = events('jdk.ObjectAllocationSample')
for event in allocation_samples:
    values = event['values']
    thread = values['eventThread']['javaThreadId']
    if thread not in seen:
        seen.add(thread)
        excluded.append({key: values[key] for key in ('eventThread', 'weight', 'startTime')})
        continue
    frames = (values.get('stackTrace') or {}).get('frames', [])
    if frames:
        sites[method(frames[0])] += values['weight']
with (output / 'allocation-sites-after-first-sample.tsv').open('w') as stream:
    stream.write('# First allocation sample per thread excluded; diagnostic weighted samples, not measured allocation totals\n')
    stream.write('weight_bytes\tpercent\tmethod\n')
    for name, weight in sites.most_common():
        stream.write(f'{weight}\t{100*weight/sum(sites.values()):.2f}\t{name}\n')
(output / 'excluded-first-allocation-samples.json').write_text(json.dumps(excluded, indent=2) + '\n')

phase_events = events('qplan.PropertyTestPhase')
phases = collections.defaultdict(list)
for event in phase_events:
    values = event['values']
    # jfr print --json represents Duration as ISO-8601 seconds.
    duration = values['duration']
    milliseconds = float(duration[2:-1]) * 1000
    phases[values['phase']].append(milliseconds)
if phases:
    with (output / 'phase-durations.tsv').open('w') as stream:
        stream.write('phase\titerations_ms\tmean_ms\tmean_excluding_first_ms\n')
        for name, durations in phases.items():
            remaining = durations[1:] or durations
            stream.write(f'{name}\t{json.dumps(durations)}\t{sum(durations)/len(durations):.6f}\t{sum(remaining)/len(remaining):.6f}\n')
print(recording.name, len(samples), 'CPU samples;', len(allocation_samples), 'allocation samples')
