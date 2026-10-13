#!/usr/bin/env python3
"""Reproduce directed operation counts without adding production instrumentation."""
import argparse,pathlib,subprocess,tempfile,re,json
parser=argparse.ArgumentParser()
parser.add_argument('baseline_jar',type=pathlib.Path)
parser.add_argument('candidate_jar',type=pathlib.Path)
parser.add_argument('--asm',type=pathlib.Path)
parser.add_argument('--output',type=pathlib.Path,required=True)
a=parser.parse_args(); source=pathlib.Path(__file__).resolve().parent
asm=a.asm
if asm is None:
 matches=list((pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.8').glob('*/*.jar'))
 if not matches:raise SystemExit('Supply --asm with ASM 9.8 or later; it must precede the benchmark JAR on the diagnostic classpath.')
 asm=matches[0]
asm=asm.resolve();a.output.mkdir(parents=True,exist_ok=True); records=[]
with tempfile.TemporaryDirectory(prefix='runtime2-local-counts-') as td:
 out=pathlib.Path(td)
 cp=str(a.baseline_jar.resolve())+':'+str(asm)
 subprocess.run(['javac','-cp',cp,'-d',str(out)]+[str(source/n) for n in ['LocalWorkProbe.java','MaterializeGrowthProbe.java','LocalLoopsGrowthProbe.java']],check=True)
 manifest=out/'MANIFEST.MF';manifest.write_text('Premain-Class: LocalWorkProbe\n\n')
 jar=out/'probe.jar';cmd=['jar','cfm',str(jar),str(manifest)]
 for f in out.glob('LocalWorkProbe*.class'):cmd+=['-C',str(out),f.name]
 subprocess.run(cmd,check=True)
 for label,jmh in [('baseline',a.baseline_jar),('candidate',a.candidate_jar)]:
  for probe in ['MaterializeGrowthProbe','LocalLoopsGrowthProbe']:
   # JMH fat JAR contains an older ASM: explicit ordering is necessary for instrumentation.
   cmd=['java','-javaagent:'+str(jar),'-cp',':'.join(map(str,[out,asm,jmh.resolve()])),probe]
   r=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
   (a.output/(label+'-'+probe+'.txt')).write_text(r.stdout)
   if r.returncode or 'Unsupported class file' in r.stdout:raise RuntimeError(r.stdout)
   for line in r.stdout.splitlines():
    if not line.startswith('WORK_COUNT '):continue
    parts=line.split(); row={'variant':label,'probe':parts[1],**{k:int(v) for k,v in (p.split('=') for p in parts[2:])}}
    n=row['width']
    if label=='baseline':
     expected={'materialize':('prefixCopies',n*(n-1)//2),'passive':('fieldKeyReads',n*n+2*n),'activation':('awaitRegistrations',n*(n+1)//2)}
     k,v=expected[row['probe']];assert row[k]==v,row
    else:
     assert row['prefixCopies']==0 and row['awaitRegistrations']==0,row
     if row['probe']=='passive':assert row['fieldKeyReads']<=4*n,row
     if row['probe']=='activation':assert row['completionSends']==n,row
    records.append(row)
 assert len(records)==24,records
(a.output/'operation-counts.json').write_text(json.dumps(records,indent=2)+'\n')
print(json.dumps(records,indent=2))
