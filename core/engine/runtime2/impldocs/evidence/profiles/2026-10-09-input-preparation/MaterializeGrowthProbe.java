import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import viaduct.engine.runtime2.model.MaterializeSelection;
import viaduct.engine.runtime2.model.MaterializeSelectionKt;
import viaduct.engine.runtime2.model.ObjectEngineResult;
import viaduct.engine.runtime2.model.testing.TestWorld;
import viaduct.engine.runtime2.resolution.MaterializeResolverInputKt;
import viaduct.engine.runtime2.resolution.framework.CycleCheckState;
import viaduct.engine.runtime2.resolution.framework.CycleTask;
import viaduct.engine.runtime2.model.InclusionCondition;
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext;
import viaduct.graphql.schema.ViaductSchema;
public class MaterializeGrowthProbe {
 public static void main(String[] args)throws Exception{
  for(int n:new int[]{16,32,64,128}){
   StringBuilder s=new StringBuilder("type Query {");for(int i=0;i<n;i++)s.append(" f").append(i).append(": Int");s.append(" }");
   var world=TestWorld.Companion.fromSDL(s.toString(),x->Collections.emptyMap(),x->Collections.emptyMap(),x->Collections.emptyMap(),x->Collections.emptyMap(),x->Collections.emptyMap(),false);
   var type=(ViaductSchema.Object)world.getSchema().getTypes().get("Query");
   Map<ObjectEngineResult.ObjectKey,Object> values=new LinkedHashMap<>();List<MaterializeSelection> selections=new ArrayList<>();
   for(var field:type.getFields()){
    if(!field.getName().startsWith("f"))continue;
    var key=ObjectEngineResult.GroundKey.Companion.of(field,Collections.emptyMap());values.put(key,1);
    selections.add(MaterializeSelection.Companion.of(field.getName(),key,Collections.singleton(type),MaterializeSelectionKt.materializeSelectionForestOf(),InclusionCondition.Always.INSTANCE,null));
   }
   selections.add(MaterializeSelection.Companion.of("excluded",values.keySet().iterator().next(),Collections.singleton(type),MaterializeSelectionKt.materializeSelectionForestOf(),InclusionCondition.Never.INSTANCE,null));
   Method of=Arrays.stream(ObjectEngineResult.Companion.getClass().getDeclaredMethods()).filter(m->m.getName().equals("of$default")).findFirst().orElseThrow();
   var root=(ObjectEngineResult)of.invoke(null,ObjectEngineResult.Companion,type,values,null,null,false,12,null);
   Method create=Arrays.stream(SharedOperationContext.Companion.getClass().getDeclaredMethods()).filter(m->m.getName().equals("create$default")&&m.getParameterCount()==8).findFirst().orElseThrow();
   var op=(SharedOperationContext<?>)create.invoke(null,SharedOperationContext.Companion,world.getAssumptions(),null,null,null,null,30,null);
   var reader=CycleTask.Companion.fieldResolver(root,Collections.emptyList());
   Continuation<viaduct.engine.api.EngineObjectData.Sync> cont=new Continuation<>(){public CoroutineContext getContext(){return EmptyCoroutineContext.INSTANCE;}public void resumeWith(Object value){throw new AssertionError("Unexpected suspension");}};
   LocalWorkProbe.begin();var value=MaterializeResolverInputKt.materializeResolverInput(root,op,CycleCheckState.Companion.createNOP(),MaterializeSelectionKt.toMaterializeSelectionForest(selections),reader,Collections.emptyList(),false,cont);LocalWorkProbe.end("materialize",n);
   if(value==kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED())throw new AssertionError("Unexpected suspension");
   var input=(viaduct.engine.api.EngineObjectData.Sync)value;int count=0;
   for(String name:input.getSelections()){count++;if(!Integer.valueOf(1).equals(input.get(name)))throw new AssertionError("Wrong selected value");}
   if(count!=n || input.isPresent("excluded"))throw new AssertionError("Wrong selected fields");
  }
 }
}
