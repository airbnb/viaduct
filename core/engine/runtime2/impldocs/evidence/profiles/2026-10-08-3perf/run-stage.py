from pathlib import Path
import datetime
import hashlib
import json
import subprocess
import sys
import zipfile

root = Path('/home/raymie_stata/repos/3ht')
oss = root / 'projects/viaduct/oss'
out = Path(__file__).resolve().parent
stage = sys.argv[1]
stage_dir = out / stage
stage_dir.mkdir(exist_ok=True)
java = '/opt/mise/installs/java/corretto-21.0.4.7.1/bin/java'

def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def git(*args):
    return subprocess.check_output(['git', *args], cwd=root, text=True).strip()

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

assert not git('status', '--porcelain'), 'Measurement requires a clean committed tree'
meta = {'stage': stage, 'revision': git('rev-parse', 'HEAD'), 'branch': git('branch', '--show-current'), 'started_utc': now(), 'commands': []}

def run(name, command, cwd):
    print('Running', stage, name, flush=True)
    record = {'name': name, 'command': command, 'cwd': str(cwd), 'started_utc': now()}
    with (stage_dir / (name + '.txt')).open('w') as log:
        result = subprocess.run(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT)
    record.update(exit_code=result.returncode, finished_utc=now())
    meta['commands'].append(record)
    (stage_dir / 'source-state.json').write_text(json.dumps(meta, indent=2) + '\n')
    if result.returncode:
        print((stage_dir / (name + '.txt')).read_text()[-6000:], flush=True)
        raise SystemExit(result.returncode)

run('build', ['./gradlew', ':core:engine:runtime2:jmhJar', '--no-configuration-cache', '--console=plain'], oss)
jar = root / 'dist/gradle/viaduct-oss/core/build/engine/runtime2/libs/engine-runtime2-2.1.0-SNAPSHOT-jmh.jar'
snapshot = stage_dir / 'benchmark.jar'
snapshot.write_bytes(jar.read_bytes())
meta['jar_sha256'] = sha(snapshot)
with zipfile.ZipFile(snapshot) as z:
    relevant = {n: hashlib.sha256(z.read(n)).hexdigest() for n in sorted(z.namelist()) if n.startswith('viaduct/engine/runtime2/') and (n.endswith('.class') or '/benchmark/current-profile/' in n)}
meta['runtime2_jar_content_sha256'] = hashlib.sha256(json.dumps(relevant, sort_keys=True).encode()).hexdigest()
(stage_dir / 'jar-entries.json').write_text(json.dumps(relevant, indent=2) + '\n')
meta['corpus_sha256'] = {str(p.relative_to(oss)): sha(p) for p in sorted((oss / 'core/engine/runtime2/src/jmh/resources/viaduct/engine/runtime2/benchmark/current-profile').glob('*')) if p.is_file()}
meta['source_sha256'] = {}
for relative in git('ls-files', 'projects/viaduct/oss/core/engine/runtime2/src', 'projects/viaduct/oss/core/engine/runtime2/build.gradle.kts', 'projects/viaduct/oss/core/engine/runtime2/development.gradle.kts').splitlines():
    p = root / relative
    if p.is_file() and (p.suffix == '.kt' or relative.endswith('.gradle.kts')):
        meta['source_sha256'][relative] = sha(p)
assert not git('status', '--porcelain'), 'Build changed the committed tree'
run('benchmark', [java, '-jar', str(snapshot), 'viaduct\\.engine\\.runtime2\\.resolution\\.ResolverBenchmark\\.overhead', '-p', 'loopCount=1', '-wi', '5', '-i', '8', '-f', '3', '-t', '1', '-foe', 'true', '-rf', 'json', '-rff', str(stage_dir / 'benchmark.json'), '-jvmArgsAppend', '-Dviaduct.resolution.threadcount=1 -DresolverBenchmarkReportFile=' + str(stage_dir / 'statistics.txt')], oss)
row = json.loads((stage_dir / 'benchmark.json').read_text())[0]
metric = row['primaryMetric']
assert metric['scoreUnit'] == 's/op', metric['scoreUnit']
meta['result'] = {'ms_per_resolution': metric['score'] * 10, 'error_ms_per_resolution': metric['scoreError'] * 10, 'unit': metric['scoreUnit'], 'resolutions_per_operation': 100, 'forks': row['forks'], 'warmup_iterations': row['warmupIterations'], 'measurement_iterations': row['measurementIterations']}
assert len(metric['rawData']) == 3 and all(len(f) == 8 for f in metric['rawData'])
meta['finished_utc'] = now()
(stage_dir / 'source-state.json').write_text(json.dumps(meta, indent=2) + '\n')
with (stage_dir / 'measured-iterations.tsv').open('w') as f:
    f.write('stage\tfork\titeration\ts_per_100_resolutions\tms_per_resolution\n')
    for fork, values in enumerate(metric['rawData'], 1):
        for iteration, value in enumerate(values, 1):
            f.write(f'{stage}\t{fork}\t{iteration}\t{value:.9f}\t{value * 10:.9f}\n')
assert not git('status', '--porcelain'), 'Benchmark changed the committed tree'
print(json.dumps(meta['result'], indent=2), flush=True)
