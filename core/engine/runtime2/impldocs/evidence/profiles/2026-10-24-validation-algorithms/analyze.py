import bisect, collections, datetime, json, statistics, sys
from pathlib import Path
source=Path(sys.argv[1]); out=Path(sys.argv[2])
events=json.load(source.open())['recording']['events']
def timestamp(s): return datetime.datetime.fromisoformat(s.replace('Z','+00:00')).timestamp()
def seconds(s): return float(s[2:-1])
def frames(v):
    return [(f['method']['type']['name'].replace('/','.')+'.'+f['method']['name'],f.get('lineNumber',-1)) for f in (v.get('stackTrace') or {}).get('frames',[])]
phases=[]; durations=collections.defaultdict(list)
for e in events:
    if e['type']=='qplan.PropertyTestPhase':
        v=e['values']; start=timestamp(v['startTime']); duration=seconds(v['duration'])
        phases.append((start,start+duration,v['phase']))
        durations[v['phase']].append(duration)
phases.sort(); starts=[p[0] for p in phases]
def phaseof(v):
    t=timestamp(v['startTime']); i=bisect.bisect_right(starts,t)-1
    return phases[i][2] if i>=0 and t<=phases[i][1] else 'outside phases'
flat=collections.defaultdict(collections.Counter); inclusive=collections.defaultdict(collections.Counter)
lines=collections.defaultdict(collections.Counter); n=collections.Counter(); threads=collections.Counter()
alloc=collections.defaultdict(collections.Counter); alloc_inclusive=collections.defaultdict(collections.Counter)
alloc_classes=collections.defaultdict(collections.Counter); alloc_n=collections.Counter(); alloc_total=collections.Counter()
first_alloc=set(); excluded=[]; samples=[]; allocation_samples=[]; gc=[]
for e in sorted(events,key=lambda e:e['values']['startTime']):
    typ=e['type']; v=e['values']; fs=frames(v)
    if typ in ('jdk.ExecutionSample','jdk.NativeMethodSample'):
        thread=v.get('sampledThread') or {}; name=thread.get('javaName') or thread.get('osName') or ''
        threads[name]+=1
        if not fs or not any(m.startswith('viaduct.engine.runtime2.') for m,line in fs): continue
        phase=phaseof(v); categories=[phase]
        if phase in ('correctResolution','application identity oracle','from-field binding oracle'): categories.append('ALL VALIDATION')
        for cat in categories:
            n[cat]+=1; flat[cat][fs[0][0]]+=1; inclusive[cat].update(set(m for m,line in fs)); lines[cat][str(fs[0])]+=1
        samples.append({'phase':phase,'time':v['startTime'],'thread':name,'frames':fs})
    elif typ=='jdk.ObjectAllocationSample':
        thread=v.get('eventThread') or {}; tid=thread.get('javaThreadId',thread.get('osThreadId'))
        weight=v['weight']
        if tid not in first_alloc:
            first_alloc.add(tid); excluded.append({'thread':thread.get('javaName'),'weight':weight}); continue
        if not fs or not any(m.startswith('viaduct.engine.runtime2.') for m,line in fs): continue
        phase=phaseof(v); categories=[phase]
        if phase in ('correctResolution','application identity oracle','from-field binding oracle'): categories.append('ALL VALIDATION')
        for cat in categories:
            alloc_n[cat]+=1; alloc_total[cat]+=weight; alloc[cat][fs[0][0]]+=weight
            for m in set(m for m,line in fs): alloc_inclusive[cat][m]+=weight
            alloc_classes[cat][v['objectClass']['name']]+=weight
        allocation_samples.append({'phase':phase,'time':v['startTime'],'weight':weight,'frames':fs})
    elif typ=='jdk.GCPhasePause': gc.append(seconds(v['duration']))
case_count=max(map(len,durations.values()))
result={'source':str(source),'phase_durations_ms':{},'samples_by_phase':dict(n),'threads':dict(threads),'excluded_first_allocation_samples':excluded,'gc_pause_ms':1000*sum(gc),'gc_pause_count':len(gc)}
for name,ds in durations.items():
    result['phase_durations_ms'][name]={'n':len(ds),'mean':1000*statistics.mean(ds),'median':1000*statistics.median(ds),'min':1000*min(ds),'max':1000*max(ds),'iterations':[1000*d for d in ds]}
result['cpu']={p:{'samples':n[p],'self':flat[p].most_common(40),'inclusive':inclusive[p].most_common(70),'self_lines':lines[p].most_common(30)} for p in n}
result['allocation']={p:{'samples':alloc_n[p],'bytes':alloc_total[p],'sites':alloc[p].most_common(35),'inclusive':alloc_inclusive[p].most_common(70),'classes':alloc_classes[p].most_common(20)} for p in alloc_total}
out.with_suffix('.json').write_text(json.dumps(result,indent=2))
out.with_name(out.name+'-samples.json').write_text(json.dumps(samples))
out.with_name(out.name+'-allocations.json').write_text(json.dumps(allocation_samples))
with out.with_suffix('.txt').open('w') as f:
    def emit(*args): print(*args,file=f)
    emit('PHASE WALL TIMES (ms/case; JFR diagnostic, 100 cases)')
    for p,r in result['phase_durations_ms'].items(): emit(p,r['n'],'mean',round(r['mean'],3),'median',round(r['median'],3),'min',round(r['min'],3),'max',round(r['max'],3))
    emit('GC pauses',len(gc),'sum ms',round(sum(gc)*1000,3))
    emit('CPU samples by phase',dict(n)); emit('Thread samples',dict(threads)); emit('Excluded carry-in allocation weights',excluded)
    for p in ['ALL VALIDATION','application identity oracle','correctResolution','from-field binding oracle','Resolution']:
        emit('\nPHASE',p,'CPU SAMPLES',n[p])
        emit('SELF methods')
        for m,c in flat[p].most_common(18): emit(c,round(100*c/n[p],2),m)
        emit('INCLUSIVE runtime2 methods (overlap)')
        for m,c in [(m,c) for m,c in inclusive[p].most_common() if m.startswith('viaduct.engine.runtime2.')][:40]: emit(c,round(100*c/n[p],2),m)
        emit('SAMPLED ALLOCATION BYTES',alloc_total[p], 'per case',round(alloc_total[p]/case_count))
        for m,c in alloc[p].most_common(18): emit(c,round(100*c/alloc_total[p],2),m)
        emit('INCLUSIVE allocation methods (overlap)')
        for m,c in [(m,c) for m,c in alloc_inclusive[p].most_common() if m.startswith('viaduct.engine.runtime2.')][:30]: emit(c,round(100*c/alloc_total[p],2),m)
print(out.with_suffix('.txt'))
