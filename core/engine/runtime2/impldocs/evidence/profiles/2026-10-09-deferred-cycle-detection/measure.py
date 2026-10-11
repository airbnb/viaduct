from pathlib import Path
import datetime,gzip,hashlib,json,subprocess,zipfile
out=Path(__file__).resolve().parent
root=Path('/home/raymie_stata/repos/3ht/projects/viaduct/oss')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
variants={}
for name in ['baseline','candidate']:
    jar=out/(name+'.jar');content=hashlib.sha256()
    with zipfile.ZipFile(jar) as z:
        for n in sorted(z.namelist()):
            if not n.endswith('/'):content.update(n.encode());content.update(z.read(n))
    meta={'base_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'jar_sha256':sha(jar),'jar_content_sha256':content.hexdigest(),'corpus':{str(p.relative_to(root)):sha(p) for p in sorted((root/'core/engine/runtime2/src/jmh/resources/viaduct/engine/runtime2/benchmark/current-profile').iterdir())}}
    if name=='candidate':
        paths=subprocess.check_output(['git','diff','--name-only','--relative'],cwd=root,text=True).splitlines()+subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=root,text=True).splitlines()
        paths=[p for p in paths if '/src/' in p]
        meta['source_files']={p:sha(root/p) for p in sorted(paths)}
        patch=subprocess.check_output(['git','diff','--relative','--',*paths],cwd=root)
        for p in paths:
            if subprocess.run(['git','ls-files','--error-unmatch',p],cwd=root,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
                d=subprocess.run(['git','diff','--no-index','--','/dev/null',p],cwd=root,stdout=subprocess.PIPE)
                assert d.returncode==1;patch+=d.stdout
        (out/'candidate-source.patch.gz').write_bytes(gzip.compress(patch))
        meta['git_status']=subprocess.check_output(['git','status','--short'],cwd=root,text=True)
    else:
        previous=json.loads(Path('/tmp/runtime2-cycle-consolidated-20261009/final-source.json').read_text())
        assert meta['jar_sha256']==previous['jar_sha256']
        assert meta['corpus']==previous['corpus']
        meta['source_trees']=previous['source_trees']
    (out/(name+'-source.json')).write_text(json.dumps(meta,indent=2)+'\n');variants[name]=meta
commands=[]
for name,variant,kind in [('baseline-overhead','baseline','threads1'),('candidate-overhead','candidate','threads1'),('candidate-threads5','candidate','threads5'),('baseline-threads5','baseline','threads5'),('baseline-property','baseline','property'),('candidate-property','candidate','property')]:
    if kind.startswith('threads'):
        n=int(kind.removeprefix('threads'));target=r'viaduct\.engine\.runtime2\.resolution\.ResolverBenchmark\.overhead';params=['-p','loopCount=1'];opts=['-jvmArgsAppend',f'-Dviaduct.resolution.threadcount={n} -DresolverBenchmarkReportFile='+str(out/(name+'-statistics.txt'))]
    else:
        target=r'viaduct\.engine\.runtime2\.resolution\.PropertyTestBenchmark\.propertyTest';params=['-p','loopCount=1'];opts=[]
    cmd=['java','-jar',str(out/(variant+'.jar')),target,*params,'-wi','5','-i','8','-f','3','-foe','true','-rf','json','-rff',str(out/(name+'.json')),*opts]
    meta={'name':name,'source':variants[variant],'command':cmd,'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
    print('Measuring',name,flush=True)
    with (out/(name+'.txt')).open('w') as f:p=subprocess.run(cmd,cwd=root,stdout=f,stderr=subprocess.STDOUT)
    meta['exit_code']=p.returncode;meta['finished_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat();commands.append(meta)
    (out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
    if p.returncode:raise RuntimeError(name+' failed')
    r=json.loads((out/(name+'.json')).read_text())[0]['primaryMetric'];print(name,r['score'],r['scoreError'],r['scoreUnit'],flush=True)
