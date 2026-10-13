import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.lang.reflect.Method;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import viaduct.engine.runtime2.model.Arguments;
import viaduct.engine.runtime2.model.ObjectEngineResult;
import viaduct.engine.runtime2.model.testing.TestWorld;
import viaduct.engine.runtime2.resolution.InclusionConditionActivationKt;
import viaduct.engine.runtime2.resolution.ResolverKt;
import viaduct.engine.runtime2.model.InclusionCondition;
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext;
import viaduct.graphql.schema.ViaductSchema;
public class LocalLoopsGrowthProbe {
 public static void main(String[] args)throws Exception{
  for(int n:new int[]{16,32,64,128}){
   StringBuilder fields=new StringBuilder(), values=new StringBuilder(), names=new StringBuilder();
   for(int j=0;j<n;j++){fields.append(" f").append(j).append(": Int");values.append(" f").append(j).append(": 1");names.append(" f").append(j);}
   var world=TestWorld.Companion.fromDSL("extend type Query { root: Box @resolver(result: {"+values+"}) } type Box {"+fields+"}",true,x->Collections.emptyMap(),x->Collections.emptyMap());
   var selections=world.selectionsFrom("fragment F on Query { root {"+names+"} }").getSecond();
   Method create=Arrays.stream(SharedOperationContext.Companion.getClass().getDeclaredMethods()).filter(m->m.getName().equals("create$default")&&m.getParameterCount()==8).findFirst().orElseThrow();
   var op=(SharedOperationContext<?>)create.invoke(null,SharedOperationContext.Companion,world.getAssumptions(),null,null,null,null,30,null);
   LocalWorkProbe.begin();var root=ResolverKt.resolve(op,selections,EmptyCoroutineContext.INSTANCE);LocalWorkProbe.end("passive",n);
   if(root.getKeys().size()!=1)throw new AssertionError("Wrong root fields");
   var box=(ObjectEngineResult)root.getCell(root.getKeys().iterator().next()).getValue().get();
   if(box.getKeys().size()!=n)throw new AssertionError("Wrong passive field count");
   for(var key:box.getKeys())if(!Integer.valueOf(1).equals(box.getCell(key).getValue().get()))throw new AssertionError("Wrong passive value");
   var query=(ViaductSchema.Object)world.getSchema().getTypes().get("Query");var field=query.getFields().iterator().next();
   List<InclusionCondition> leaves=new ArrayList<>();for(int j=0;j<n;j++)leaves.add(InclusionCondition.Companion.requires(Collections.singletonMap(Arguments.Variable.Companion.of(field,"v"+j),true)));
   var condition=InclusionCondition.Companion.anyOf(leaves);
   LocalWorkProbe.begin();boolean included=BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,cont)->InclusionConditionActivationKt.includeAnyReadyAlternative(condition,(variable,c)->false,cont));LocalWorkProbe.end("activation",n);
   if(included)throw new AssertionError("False bindings activated demand");
  }
 }
}
