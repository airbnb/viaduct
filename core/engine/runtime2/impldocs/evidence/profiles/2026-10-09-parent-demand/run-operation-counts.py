import argparse,json,pathlib,subprocess,tempfile
parser=argparse.ArgumentParser();parser.add_argument('baseline',type=pathlib.Path);parser.add_argument('candidate',type=pathlib.Path);parser.add_argument('--output',required=True,type=pathlib.Path);parser.add_argument('--asm',type=pathlib.Path)
a=parser.parse_args();source=pathlib.Path(__file__).resolve().parent
a.output.mkdir(parents=True,exist_ok=True)
asm=a.asm or next((pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.8').glob('*/*.jar'))
rows=[]
with tempfile.TemporaryDirectory(prefix='runtime2-parent-counts-') as td:
 out=pathlib.Path(td)
 subprocess.run(['javac','-cp',str(asm)+':'+str(a.baseline.resolve()),'-d',str(out),str(source/'ParentWorkProbe.java'),str(source/'ParentGrowthProbe.java')],check=True)
 manifest=out/'MANIFEST.MF';manifest.write_text('Premain-Class: ParentWorkProbe\n\n')
 agent=out/'probe.jar';cmd=['jar','cfm',str(agent),str(manifest)]
 for f in out.glob('ParentWorkProbe*.class'):cmd+=['-C',str(out),f.name]
 subprocess.run(cmd,check=True)
 for label,jmh in [('baseline',a.baseline),('candidate',a.candidate)]:
  cmd=['java','-javaagent:'+str(agent),'-cp',':'.join(map(str,[out,asm,jmh.resolve()])),'ParentGrowthProbe']+(['larger'] if label=='candidate' else [])
  r=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
  (a.output/(label+'.txt')).write_text(r.stdout)
  if r.returncode:raise RuntimeError(r.stdout)
  for line in r.stdout.splitlines():
   if not line.startswith('PARENT_COUNT '):continue
   values=dict(item.split('=') for item in line.split()[1:]);row={'variant':label,**{k:(v if k=='shape' else int(v)) for k,v in values.items()}}
   if label=='candidate':
    assert row['checked']==row['unchecked']==1,row
    assert row['requests']<=8*(row['depth']+1),row
    assert row['normalizedElements']<=64*(row['depth']+1),row
   rows.append(row)
 assert len(rows)==20,rows
(a.output/'operation-counts.json').write_text(json.dumps(rows,indent=2)+'\n')
print(json.dumps(rows,indent=2))
