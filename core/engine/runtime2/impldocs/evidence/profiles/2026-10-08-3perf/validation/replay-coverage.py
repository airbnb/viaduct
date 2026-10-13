from pathlib import Path
import datetime,json,subprocess
from defusedxml import ElementTree as ET
root=Path('/home/raymie_stata/repos/3ht')
oss=root/'projects/viaduct/oss'
out=Path('/tmp/runtime2-3perf/final-validation')
command=['./gradlew', ':core:engine:runtime2:resolverPropertyReplay','-PresolverPropertyClass=viaduct.engine.runtime2.resolution.TypeCheckerGeneratedTest','-PresolverPropertyProfile=resolution-type-checker-mixed','-PresolverPropertySeed=-8164484992334210930','-PresolverPropertyCase=all','-PresolverPropertySize=10:3:5','--no-configuration-cache','--console=plain']
assert not subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip()
branch=subprocess.check_output(['git','branch','--show-current'],cwd=root,text=True).strip()
records=[]
try:
 for name,target in [('coverage-candidate',branch),('coverage-baseline','rstata--baseline')]:
  if target!=branch: subprocess.run(['git','switch',target],cwd=root,check=True)
  record={'name':name,'command':command,'cwd':str(oss),'revision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
  print('Running',name,flush=True)
  with (out/(name+'.txt')).open('w') as log:
   result=subprocess.run(command,cwd=oss,stdout=log,stderr=subprocess.STDOUT)
  record.update(exit_code=result.returncode,finished_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
  messages=[]
  for p in (root/'dist/gradle/viaduct-oss/core/build/engine/runtime2/test-results/resolverPropertyReplay').glob('TEST-*.xml'):
   r=ET.parse(p).getroot()
   messages.extend(f.attrib.get('message') for f in r.findall('testcase/failure'))
   (out/(name+'-'+p.name)).write_bytes(p.read_bytes())
  record['failure_messages']=messages
  records.append(record)
  (out/'coverage-replay.json').write_text(json.dumps(records,indent=2)+'\n')
  print(name,result.returncode,messages,flush=True)
finally:
 subprocess.run(['git','switch',branch],cwd=root,check=True)
assert len(records)==2 and all(r['exit_code']==1 for r in records)
assert records[0]['failure_messages']==records[1]['failure_messages'] and len(records[0]['failure_messages'])==1
assert 'missed activated coverage [ERROR_ARGUMENT_IN_TYPE_INPUT]' in records[0]['failure_messages'][0]
print('Identical baseline and candidate coverage failure; all generated correctness cases passed.',flush=True)
