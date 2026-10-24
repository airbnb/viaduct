import hashlib
import json
import subprocess
import sys
from pathlib import Path

base = Path('/tmp/runtime2-validation-four-20261024')
name, before, after = sys.argv[1:]
java = '/opt/mise/installs/java/corretto-17.0.11.9.1/bin/java'
commands = []
scores = {}
for variant, jar in [('before', before), ('after', after)]:
    output = base / (name + '-' + variant)
    command = [java, '-Dfile.encoding=UTF-8', '-Duser.country', '-Duser.language=en', '-Duser.variant', '-jar', jar, 'viaduct.engine.runtime2.resolution.PropertyTestBenchmark.propertyTest', '-p', 'loopCount=1', '-wi', '5', '-i', '8', '-f', '3', '-foe', 'true', '-rf', 'json', '-rff', str(output.with_suffix('.json'))]
    commands.append(dict(variant=variant, command=command, jar_sha256=hashlib.sha256(Path(jar).read_bytes()).hexdigest()))
    with output.with_suffix('.txt').open('w') as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
    data = json.loads(output.with_suffix('.json').read_text())[0]
    metric = data['primaryMetric']
    scores[variant] = dict(score=metric['score'], error=metric['scoreError'], unit=metric['scoreUnit'], raw=metric['rawData'])
    print(variant, json.dumps(scores[variant]), flush=True)
result = dict(commands=commands, scores=scores, reduction_percent=100 * (1 - scores['after']['score'] / scores['before']['score']))
(base / (name + '-comparison.json')).write_text(json.dumps(result, indent=2) + '\n')
print('reduction_percent', result['reduction_percent'], flush=True)
