from pathlib import Path
import datetime
import json
import subprocess
from defusedxml import ElementTree as ET

root = Path('/home/raymie_stata/repos/3ht')
oss = root / 'projects/viaduct/oss'
out = Path(__file__).resolve().parent / 'final-validation'
out.mkdir(exist_ok=True)
records = []
for name, command in [
    ('focused', ['./gradlew', ':core:engine:runtime2:test', '--tests', 'viaduct.engine.runtime2.model.SelectionForestConcatenationTest', '--tests', 'viaduct.engine.runtime2.model.SelectionMergeTest', '--tests', 'viaduct.engine.runtime2.resolution.RegistrySuccessorDemandCacheTest', '--tests', 'viaduct.engine.runtime2.contract.CheckerQueryResolverWitnessTest', '--tests', 'viaduct.engine.runtime2.resolution.FullBenchmarkReplayTest', '--tests', 'viaduct.engine.runtime2.resolution.ResolverFromFieldBindingOracleTest', '--tests', 'viaduct.engine.runtime2.benchmark.ChinaActiveBenchmarkCheckersTest', '--tests', 'viaduct.engine.runtime2.arbitrary.PrecomputedResolverOutputsTest', '--no-configuration-cache', '--console=plain']),
    ('full-check', ['./gradlew', ':core:engine:runtime2:check']),
]:
    print('Running final stack', name, flush=True)
    record = {'command': command, 'cwd': str(oss), 'revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(), 'started_utc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    with (out / (name + '.txt')).open('w') as log:
        result = subprocess.run(command, cwd=oss, stdout=log, stderr=subprocess.STDOUT)
    record.update(exit_code=result.returncode, finished_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
    destination = out / (name + '-test-results')
    destination.mkdir(exist_ok=True)
    rows = []
    for p in sorted((root / 'dist/gradle/viaduct-oss/core/build/engine/runtime2/test-results/test').glob('TEST-*.xml')):
        (destination / p.name).write_bytes(p.read_bytes())
        suite = ET.parse(p).getroot()
        row = {key: suite.attrib.get(key) for key in ['name', 'tests', 'failures', 'errors', 'skipped']}
        row['failed_tests'] = [{'name': c.attrib['name'], 'message': f.attrib.get('message'), 'detail': f.text} for c in suite.findall('testcase') for f in c.findall('failure')]
        rows.append(row)
    (out / (name + '-test-results.json')).write_text(json.dumps(rows, indent=2) + '\n')
    record['test_counts'] = {key: sum(int(row[key]) for row in rows) for key in ['tests', 'failures', 'errors', 'skipped']}
    records.append(record)
    (out / 'commands.json').write_text(json.dumps(records, indent=2) + '\n')
    print(name, result.returncode, record['test_counts'], flush=True)
    if name == 'focused' and result.returncode:
        break
