from pathlib import Path
import datetime,json,subprocess
root=Path('/home/raymie_stata/repos/3ht')
out=Path('/tmp/runtime2-3perf/final-validation')
records=[]
for name,command in [('bazel',['bazel','test','//projects/viaduct/oss/...']),('lint',['yak','lint'])]:
 print('Running final stack',name,flush=True)
 record={'command':command,'cwd':str(root),'revision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
 with (out/(name+'.txt')).open('w') as log:
  result=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT)
 record.update(exit_code=result.returncode,finished_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
 records.append(record)
 (out/'treehouse-commands.json').write_text(json.dumps(records,indent=2)+'\n')
 print(name,result.returncode,flush=True)
 if result.returncode:
  print((out/(name+'.txt')).read_text()[-5000:],flush=True)
  raise SystemExit(result.returncode)
