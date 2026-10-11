import datetime,hashlib,json,pathlib,subprocess
out=pathlib.Path(__file__).resolve().parent
import sys
root=pathlib.Path(sys.argv[1])
asm=pathlib.Path('/home/raymie_stata/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.8/dc19ecb3f7889b7860697215cae99c0f9b6f6b4b/asm-9.8.jar')
commands=[]
def run(name,cmd):
    print('Diagnostic',name,flush=True)
    meta={'name':name,'command':cmd,'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
    with (out/(name+'.txt')).open('w') as f:
        p=subprocess.run(cmd,cwd=root,stdout=f,stderr=subprocess.STDOUT)
    meta['exit_code']=p.returncode;meta['finished_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    commands.append(meta);(out/'diagnostics-commands.json').write_text(json.dumps(commands,indent=2)+'\n')
    if p.returncode: raise RuntimeError(name+' failed; see log')
run('agent-build',['javac','-cp',str(out/'final.jar')+':'+str(asm),'-d',str(out),str(out/'CycleProbe.java'),str(out/'ChainProbe.java')])
run('agent-package',['jar','cfm',str(out/'probe.jar'),str(out/'MANIFEST.MF'),'-C',str(out),'CycleProbe.class','-C',str(out),'CycleProbe$Graph.class','-C',str(out),'CycleProbe$Read.class','-C',str(out),'CycleProbe$Edge.class','-C',str(out),'CycleProbe$1.class','-C',str(out),'CycleProbe$1$1.class','-C',str(out),'CycleProbe$1$1$1.class'])
for variant in ['baseline','final']:
    run(variant+'-counts',['java','-jar',str(out/(variant+'.jar')),r'viaduct\.engine\.runtime2\.resolution\.ResolverBenchmark\.overhead','-p','loopCount=1','-wi','1','-i','1','-f','1','-foe','true','-jvmArgsAppend','-javaagent:'+str(out/'probe.jar')])
    run(variant+'-chain',['java','-javaagent:'+str(out/'probe.jar'),'-cp',str(out)+':'+str(out/(variant+'.jar')),'ChainProbe'])
print('Diagnostics complete',flush=True)
