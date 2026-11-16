import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import kotlin.Unit;
import viaduct.engine.runtime2.model.ObjectEngineResult;
import viaduct.engine.runtime2.model.Selection;
import viaduct.engine.runtime2.model.SelectionForest;
import viaduct.engine.runtime2.schema.NormalizedDocumentSelectionBenchmark;

/** Structural census, run outside JMH; identity counts are not semantic selection equality. */
public class ForestShape {
    public static void main(String[] args) {
        var fixture = new NormalizedDocumentSelectionBenchmark();
        fixture.setup();
        inspect("AST", fixture.convertAst());
        inspect("eager ND", fixture.convertNormalized());
        inspect("shared ND", fixture.convertNormalizedBacked());
    }

    private static void inspect(String name, SelectionForest root) {
        var forests = new IdentityHashMap<SelectionForest, Boolean>();
        var selections = new IdentityHashMap<Selection, Boolean>();
        var keys = new IdentityHashMap<ObjectEngineResult.Key, Boolean>();
        var pending = new ArrayDeque<SelectionForest>();
        pending.add(root);
        while (!pending.isEmpty()) {
            var forest = pending.removeLast();
            if (forests.put(forest, true) != null) continue;
            forest.forEach(selection -> {
                selections.put(selection, true);
                keys.put(selection.getKey(), true);
                pending.add(selection.getSubselections());
                return Unit.INSTANCE;
            });
        }
        System.out.printf("%s: forests=%d selections=%d keys=%d logicalOccurrences=%d%n",
                name, forests.size(), selections.size(), keys.size(), count(root, new IdentityHashMap<>()));
    }

    private static long count(SelectionForest forest, IdentityHashMap<SelectionForest, Long> memo) {
        var cached = memo.get(forest);
        if (cached != null) return cached;
        long[] total = {0};
        forest.forEach(selection -> {
            total[0] += 1 + count(selection.getSubselections(), memo);
            return Unit.INSTANCE;
        });
        memo.put(forest, total[0]);
        return total[0];
    }
}
