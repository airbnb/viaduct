import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import viaduct.engine.api.CheckerResult;
import viaduct.engine.runtime2.model.Fragment;
import viaduct.engine.runtime2.model.MaterializeSelectionKt;
import viaduct.engine.runtime2.model.SelectionForest;
import viaduct.engine.runtime2.model.SelectionKt;
import viaduct.engine.runtime2.model.testing.SelectionParsingKt;
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver;
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates;
import viaduct.engine.runtime2.model.testing.TestWorld;
import viaduct.engine.runtime2.resolution.ParentConstructionDemandKt;
import viaduct.engine.runtime2.resolution.framework.Demand;
import viaduct.graphql.schema.ViaductSchema;
public class ParentGrowthProbe {
    static ViaductSchema.Object object(ViaductSchema schema, String name) { return (ViaductSchema.Object)schema.getTypes().get(name); }
    static Fragment fragment(viaduct.engine.runtime2.schema.ViaductAndGJSchema schemas, String text) {
        return SelectionParsingKt.fragmentFrom(schemas, text, Collections.emptyMap(), null, null, false);
    }
    public static TestWorld fixture(int depth, boolean nested) {
        String input = nested ? "seed { value other }" : "seed";
        var fields = new StringBuilder();
        for (int i = 0; i <= depth; i++) {
            String dependencies = i == depth ? "parent { " + input + " }" : "field" + (i+1) + (i+2 <= depth ? " field" + (i+2) : "");
            fields.append(" field").append(i).append(": Int @resolver(of: \"").append(dependencies).append("\", result: 1)");
        }
        return TestWorld.Companion.fromDSL("extend type Query { root: Parent @resolver(result: {}) } type Parent { child: Child seed: " + (nested ? "Seed" : "Int") + " } " + (nested ? "type Seed { value: Int other: Int } " : "") + "type Child { parent: Parent @parent " + fields + " }", true, schemas -> {
            var schema = schemas.getLoweredSchema();
            var pair = new ResolverFragmentTemplates(fragment(schemas, "fragment FC on Child { parent { " + input + " } }").getMaterializeSelections(), MaterializeSelectionKt.materializeSelectionForestOf(), Collections.emptyMap(), null);
            var result = new LinkedHashMap<ViaductSchema.ObjectField, FieldCheckerResolver>();
            for (var field: object(schema, "Child").getFields()) if (field.getName().startsWith("field")) result.put(field, FieldCheckerResolver.Companion.of(field, object(schema, "Query"), Collections.singletonMap("input", pair), (a,b,c,d) -> CheckerResult.Success.INSTANCE));
            return result;
        }, schemas -> Collections.emptyMap());
    }
    public static Demand<SelectionForest> lift(TestWorld world, SelectionForest selections) {
        return ParentConstructionDemandKt.liftParentConstructionDemand(Demand.Companion.checked(selections, true), world.getAssumptions());
    }
    public static void main(String[] args) {
        int[] depths = args.length == 0 ? new int[]{5,10,15,20} : new int[]{5,10,15,20,40,80};
        for (boolean nested: new boolean[]{false, true}) for (int depth: depths) {
            var world = fixture(depth, nested);
            var selections = world.selectionsFrom("fragment F on Query { root { child { field0 } } }").getSecond();
            ParentWorkProbe.begin();
            var demand = lift(world, selections);
            int checked = demand.getChecked().single().getSubselections().getSize();
            int unchecked = demand.getUnchecked().single().getSubselections().getSize();
            ParentWorkProbe.end(nested ? "nested" : "scalar", depth, checked, unchecked);
            for (var component: List.of(demand.getChecked(), demand.getUnchecked())) {
                var seeds = SelectionKt.merge(component.single().getSubselections(), object(world.getSchema(), "Parent"));
                if (seeds.getSize() != 1 || !seeds.single().getKey().getField().getName().equals("seed")) throw new AssertionError("Wrong ancestor requirement");
                if (nested && SelectionKt.merge(seeds.single().getSubselections(), object(world.getSchema(), "Seed")).getSize() != 2) throw new AssertionError("Lost nested requirement");
            }
        }
    }
}
