import datetime,json,pathlib,shutil,subprocess
from defusedxml import ElementTree as ET
root=pathlib.Path('/home/raymie_stata/repos/2ht/projects/viaduct/oss')
out=pathlib.Path('/tmp/runtime2-parent-demand-20261009/stress');out.mkdir(exist_ok=True)
results=pathlib.Path('/home/raymie_stata/repos/2ht/dist/gradle/viaduct-oss/core/build/engine/runtime2/test-results')
commands=[
 ('resolutionParentFocused',['-Pviaduct.resolution.threadcount=5']),
 ('resolutionStress',['-PresolutionStressSeed=20260809']),
 *[('resolutionFieldCheckerStress',['-PresolutionFieldCheckerStressSeed=424242','-PresolutionFieldCheckerStressProfile='+p,'-Pviaduct.resolution.threadcount=5']) for p in ['success','denial','mixed','passive','root-reference']],
 ('resolutionTypeCheckerStress',['-PresolutionTypeCheckerStressSeed=2026093001','-Pviaduct.resolution.threadcount=5']),
 ('resolutionMultithreadedStress',[]),
 ('resolver23Stress',['-Presolver23StressSeed=20260809']),
]
records=[]
for task,args in commands:
 label=task+('-'+args[1].split('=')[1] if task=='resolutionFieldCheckerStress' else '')
 cmd=['./gradlew',':core:engine:runtime2:'+task,*args,'--no-configuration-cache','--console=plain']
 start=datetime.datetime.now(datetime.timezone.utc).isoformat()
 print('START',label,start,flush=True)
 with (out/(label+'.txt')).open('w') as log:r=subprocess.run(cmd,cwd=root,stdout=log,stderr=subprocess.STDOUT)
 target=out/label;target.mkdir(exist_ok=True)
 totals=dict(tests=0,failures=0,errors=0,skipped=0)
 for xml in (results/task).glob('TEST-*.xml'):
  shutil.copy2(xml,target/xml.name)
  suite=ET.parse(xml).getroot()
  for key in totals:totals[key]+=int(suite.attrib.get(key,0))
 record=dict(task=task,command=cmd,start=start,end=datetime.datetime.now(datetime.timezone.utc).isoformat(),exit_code=r.returncode,**totals)
 records.append(record);(out/'validation.json').write_text(json.dumps(records,indent=2)+'\n')
 print('DONE',label,record,flush=True)
