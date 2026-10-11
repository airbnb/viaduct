import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.lang.reflect.Method;
import viaduct.engine.runtime2.model.ObjectEngineResult;
import viaduct.engine.runtime2.model.testing.TestWorld;
import viaduct.engine.runtime2.resolution.framework.CycleCheckState;
import viaduct.engine.runtime2.resolution.framework.CycleSlot;
import viaduct.engine.runtime2.resolution.framework.CycleTask;
import viaduct.graphql.schema.ViaductSchema;
public class ChainProbe {
 public static void main(String[] args) throws Exception {
  for (int depth:new int[]{16,32,64,128}) {
   StringBuilder s=new StringBuilder("type Query {");for(int i=0;i<=depth;i++)s.append(" f").append(i).append(": Int");s.append(" }");
   TestWorld world=TestWorld.Companion.fromSDL(s.toString(),x->Collections.emptyMap(),x->Collections.emptyMap(),x->Collections.emptyMap(),x->Collections.emptyMap(),x->Collections.emptyMap(),false);
   ViaductSchema.Object query=(ViaductSchema.Object)world.getSchema().getTypes().get("Query");
   Method of=Arrays.stream(ObjectEngineResult.Companion.getClass().getDeclaredMethods()).filter(m->m.getName().equals("of$default")).findFirst().orElseThrow();
   ObjectEngineResult root=(ObjectEngineResult)of.invoke(null,ObjectEngineResult.Companion,query,null,null,null,true,14,null);
   List<CycleTask> tasks=new ArrayList<>();List<CycleSlot> slots=new ArrayList<>();
   for(int i=0;i<=depth;i++) {
    ViaductSchema.ObjectField field=null;for(ViaductSchema.ObjectField f:query.getFields())if(f.getName().equals("f"+i))field=f;
    ObjectEngineResult.GroundKey key=ObjectEngineResult.GroundKey.Companion.of(Objects.requireNonNull(field),Collections.emptyMap());
    tasks.add(CycleTask.Companion.fieldResolver(root,Collections.singletonList(key)));
    slots.add(CycleSlot.Companion.value(root.reserveCell(key)));
   }
   CycleCheckState checker=CycleCheckState.Companion.create();
   CycleProbe.begin();
   for(int i=0;i<=depth;i++)checker.registerWriter(slots.get(i),tasks.get(i));
   for(int i=depth-1;i>=0;i--)checker.cycleCheck(tasks.get(i),slots.get(i+1));
   System.out.println("CHAIN_DEPTH="+depth);CycleProbe.end();
  }
 }
}
