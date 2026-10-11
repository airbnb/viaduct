from pathlib import Path
import datetime,json,subprocess
out=Path(__file__).resolve().parent
root=Path('/home/raymie_stata/repos/3ht/projects/viaduct/oss')
cmd=['java','-jar',str(out/'candidate.jar'),r'viaduct\.engine\.runtime2\.resolution\.ResolverBenchmark\.overhead','-p','loopCount=10','-wi','3','-i','1','-f','1','-foe','true','-jvmArgsAppend','-DresolutionOverheadProfileOutput='+str(out/'candidate-overhead.jfr')]
meta={'command':cmd,'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
with (out/'candidate-profile.txt').open('w') as f:
    p=subprocess.run(cmd,cwd=root,stdout=f,stderr=subprocess.STDOUT)
meta['exit_code']=p.returncode;meta['finished_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
(out/'profile-command.json').write_text(json.dumps(meta,indent=2)+'\n')
if p.returncode:raise RuntimeError('Profile failed')
subprocess.run([str(root/'core/engine/runtime2/export-resolver-profile.sh'),str(out/'candidate-overhead.jfr'),str(out/'candidate-overhead-profile')],check=True,cwd=root)
print('Candidate profile exported',flush=True)
