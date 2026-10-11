import datetime, hashlib, json, pathlib, subprocess, sys, zipfile
out = pathlib.Path(__file__).resolve().parent
root = pathlib.Path(sys.argv[1])
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def git(*args): return subprocess.check_output(['git', *args], cwd=root, text=True).strip()
def source(name, commit):
    jar = out / (name + '.jar')
    with zipfile.ZipFile(jar) as z:
        digest = hashlib.sha256()
        for n in sorted(z.namelist()):
            if not n.endswith('/'):
                digest.update(n.encode()); digest.update(z.read(n))
    prefix = git('rev-parse', '--show-prefix')
    paths = ['src/main', 'src/support', 'src/jmh']
    meta = {'commit': commit, 'jar_sha256': sha(jar), 'jar_content_sha256': digest.hexdigest(),
            'source_trees': {p: git('rev-parse', commit + ':' + prefix + 'core/engine/runtime2/' + p) for p in paths},
            'cycle_source_blob': git('rev-parse', commit + ':' + prefix + 'core/engine/runtime2/src/main/kotlin/viaduct/engine/runtime2/resolution/framework/CycleCheckState.kt'),
            'corpus': {str(p.relative_to(root)): sha(p) for p in sorted((root / 'core/engine/runtime2/src/jmh/resources/viaduct/engine/runtime2/benchmark/current-profile').iterdir())}}
    (out / (name + '-source.json')).write_text(json.dumps(meta, indent=2) + '\n')
    return meta
sources = {n: source(n, c) for n, c in [('baseline', 'b21ddcf895392fb97fac5004c752a48e8df88215'), ('final', git('rev-parse', 'HEAD'))]}
assert sources['baseline']['corpus'] == sources['final']['corpus']
assert sources['baseline']['source_trees']['src/support'] == sources['final']['source_trees']['src/support']
assert sources['baseline']['source_trees']['src/jmh'] == sources['final']['source_trees']['src/jmh']
commands = []
# All runs execute serially. Reverse the five-thread pair to reduce a consistent ordering bias.
for name, variant, kind in [('baseline-overhead','baseline','threads1'), ('final-overhead','final','threads1'), ('final-threads5','final','threads5'), ('baseline-threads5','baseline','threads5'), ('baseline-property','baseline','property'), ('final-property','final','property'), ('final-correctness','final','correctness'), ('baseline-correctness','baseline','correctness')]:
    if kind.startswith('threads'):
        threads = int(kind.removeprefix('threads'))
        target = r'viaduct\.engine\.runtime2\.resolution\.ResolverBenchmark\.overhead'
        params = ['-p', 'loopCount=1']
        opts = ['-jvmArgsAppend', f'-Dviaduct.resolution.threadcount={threads} -DresolverBenchmarkReportFile=' + str(out / (name + '-statistics.txt'))]
    elif kind == 'property':
        target = r'viaduct\.engine\.runtime2\.resolution\.PropertyTestBenchmark\.propertyTest'
        params = ['-p', 'loopCount=1']; opts = []
    else:
        target = r'viaduct\.engine\.runtime2\.correctresolution\.CorrectResolutionBenchmark\.correctResolution'
        params = ['-p', 'loopCount=1', '-p', 'inputCount=50', '-p', 'querySeed=1']; opts = []
    cmd = ['java', '-jar', str(out / (variant + '.jar')), target, *params, '-wi', '5', '-i', '8', '-f', '3', '-foe', 'true', '-rf', 'json', '-rff', str(out / (name + '.json')), *opts]
    meta = {'name': name, 'variant': variant, 'source': sources[variant], 'command': cmd, 'started_utc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    print('Measuring', name, flush=True)
    with (out / (name + '.txt')).open('w') as f:
        proc = subprocess.run(cmd, cwd=root, stdout=f, stderr=subprocess.STDOUT)
    meta['exit_code'] = proc.returncode; meta['finished_utc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    commands.append(meta); (out / 'measurement-commands.json').write_text(json.dumps(commands, indent=2) + '\n')
    if proc.returncode: sys.exit(proc.returncode)
    r = json.loads((out / (name + '.json')).read_text())[0]['primaryMetric']
    print(name, r['score'], '+/-', r['scoreError'], r['scoreUnit'], flush=True)
