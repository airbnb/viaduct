import argparse,json,pathlib,shutil,subprocess,tempfile
p=argparse.ArgumentParser();p.add_argument('baseline',type=pathlib.Path);p.add_argument('candidate',type=pathlib.Path);p.add_argument('--output',type=pathlib.Path,required=True)
a=p.parse_args();src=pathlib.Path(__file__).resolve().parent;a.output.mkdir(parents=True,exist_ok=True)
cache=pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1'
deps=[next((cache/'org.ow2.asm/asm/9.8').glob('*/*.jar')),*[next((cache/'org.openjdk.jmh'/artifact/'1.36').glob('*/*.jar')) for artifact in ['jmh-generator-bytecode','jmh-generator-reflection']]]
with tempfile.TemporaryDirectory(prefix='runtime2-parent-jmh-') as td:
 root=pathlib.Path(td);classes=root/'classes';classes.mkdir();generated=root/'generated';resources=root/'resources'
 cp=':'.join(map(str,[classes,*deps,a.baseline.resolve()]))
 subprocess.run(['javac','-cp',cp,'-d',str(classes),*[str(src/name) for name in ['ParentGrowthProbe.java','ParentWorkProbe.java','ParentDemandBenchmark.java']]],check=True)
 subprocess.run(['java','-cp',cp,'org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator',str(classes),str(generated),str(resources),'reflection'],check=True)
 subprocess.run(['javac','-cp',cp,'-d',str(classes),*[str(s) for s in generated.rglob('*.java')]],check=True)
 shutil.copytree(resources,classes,dirs_exist_ok=True)
 records=[]
 for variant,jar in [('baseline',a.baseline),('candidate',a.candidate)]:
  cp=':'.join(map(str,[classes,*deps,jar.resolve()]))
  cmd=['java','-cp',cp,'org.openjdk.jmh.Main','parentprofile.ParentDemandBenchmark.lift','-foe','true','-prof','gc','-jvmArgsAppend','-Xms2g -Xmx2g','-rf','json','-rff',str((a.output/(variant+'.json')).resolve())]
  with (a.output/(variant+'.txt')).open('w') as log:subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,check=True)
  records.append({'variant':variant,'command':cmd})
 (a.output/'commands.json').write_text(json.dumps(records,indent=2)+'\n')
