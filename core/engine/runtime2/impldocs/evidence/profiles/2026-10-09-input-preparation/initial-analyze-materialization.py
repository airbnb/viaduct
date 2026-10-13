import json, subprocess, collections, pathlib, sys
root=pathlib.Path(sys.argv[1]); recording=pathlib.Path(sys.argv[2])
def events(types):
 return json.loads(subprocess.check_output(['jfr','print','--json','--events',types,'--stack-depth','256',str(recording)]))['recording']['events']
def methods(e):
 return [f['method']['type']['name']+'.'+f['method']['name'] for f in (e['values'].get('stackTrace') or {}).get('frames',[])]
def tags(ms):
 def has(s): return any(s in m for m in ms)
 def end(s): return any(m.endswith(s) for m in ms)
 return {
  'input_projection':has('resolution/ResolverInputMaterializationLogic'),
  'projection_collect':has('resolution/ResolverInputMaterializationLogic') and has('MaterializeSelectionForestImpl.collect'),
  'projection_filter_inclusions':has('ResolverInputMaterializationLogic.fetchIncluded'),
  'projection_eod_construction':has('resolution/ResolverInputMaterializationLogic') and has('QPlanEngineObjectDataKt.constructEngineObjectData'),
  'projection_checked_read':has('resolution/ResolverInputMaterializationLogic') and has('materializeCheckedValueForResolver'),
  'projection_resolver_caller':has('resolution/ResolverInputMaterializationLogic') and has('FieldResolutionLogic'),
  'projection_checker_caller':has('resolution/ResolverInputMaterializationLogic') and (has('FieldCheckerTask') or has('TypeCheckerTask')),
  'checker_template_lowering':has('CheckerResolverBaseKt.lowerForResolution'),
  'materialization_instantiation':has('instantiateObjectMaterializationSelections') or has('instantiateQueryMaterializationSelections'),
  'input_projection_or_instantiation':has('resolution/ResolverInputMaterializationLogic') or has('instantiateObjectMaterializationSelections') or has('instantiateQueryMaterializationSelections'),
  'output_snipping':has('SnipToDemandKt'),
  'output_construction':has('QPlanEngineObjectDataKt.constructEngineObjectData'),
  'inclusion_activation':has('InclusionConditionActivationKt'),
  'passive_fields_inclusive':has('SharedPassiveValueResolutionLogic.materializePassiveFields'),
  'passive_fields_local':has('SharedPassiveValueResolutionLogic.materializePassiveFields') and not has('SharedPassiveValueResolutionLogic.resolvePassiveValues') and not has('SelectionKt.merge'),
  'selection_validation':has('SelectionKt.validateSelection'),
  'materialize_selection_creation':has('MaterializeSelection$Companion.of'),
 }
samples=events('jdk.ExecutionSample'); counts=collections.Counter(); groups=collections.defaultdict(collections.Counter)
for e in samples:
 ms=methods(e); counts.update(k for k,v in tags(ms).items() if v)
 if tags(ms)['input_projection']:
  groups['projection_hot_leaf'][ms[0]]+=1
  for m in set(ms):groups['projection_inclusive'][m]+=1
allocs=events('jdk.ObjectAllocationSample'); first=set(); total=0; weights=collections.Counter(); perclass=collections.Counter()
for e in allocs:
 v=e['values']; thread=v['eventThread']['javaThreadId']
 if thread not in first:first.add(thread);continue
 ms=methods(e); w=v['weight'];total+=w
 for k,p in tags(ms).items():
  if p:weights[k]+=w
 if tags(ms)['input_projection']: perclass[v['objectClass']['name']]+=w
out={'recording':str(recording),'cpu_samples':len(samples),'cpu_components':{k:{'count':v,'percent':100*v/len(samples)} for k,v in counts.items()},'projection_details':{k:v.most_common(40) for k,v in groups.items()},'allocation_samples':len(allocs),'weighted_bytes_excluding_first_per_thread':total,'allocation_components':{k:{'weight':v,'percent':100*v/total} for k,v in weights.items()},'projection_allocated_classes':perclass.most_common(20)}
(root/(recording.stem+'-materialization.json')).write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps(out,indent=2))
def projection_category(ms):
 def has(s):return any(s in m for m in ms)
 if has('CycleCheckStateImpl'):return 'cycle bookkeeping'
 if has('ResolverInputMaterializationLogic.fetchIncluded'):return 'inclusion filtering/copying'
 if has('MaterializeSelectionForestImpl.collect'):return 'response-key collection'
 if has('GroundObjectKeyKt'):return 'argument grounding'
 if has('QPlanEngineObjectDataKt.constructEngineObjectData'):return 'EOD construction/validation'
 if has('materializeCheckedValueForResolver') or has('CheckedEngineResultKt') or has('ObjectEngineResultImpl.reserveCell') or has('Promise') or has('CellImpl'):return 'cells/promises/checked reads'
 return 'other traversal/conversion'
cats=collections.Counter(); wts=collections.Counter();first=set()
for e in samples:
 ms=methods(e)
 if tags(ms)['input_projection']:cats[projection_category(ms)]+=1
for e in allocs:
 v=e['values'];thread=v['eventThread']['javaThreadId']
 if thread not in first:first.add(thread);continue
 ms=methods(e)
 if tags(ms)['input_projection']:wts[projection_category(ms)]+=v['weight']
(root/(recording.stem+'-projection-partition.json')).write_text(json.dumps({'samples':len(samples),'projection_samples':sum(cats.values()),'cpu':{k:{'count':v,'percent_all':100*v/len(samples),'percent_projection':100*v/sum(cats.values())} for k,v in cats.items()},'allocation_weights':dict(wts)},indent=2)+'\n')
