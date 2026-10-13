package parentprofile;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import viaduct.engine.runtime2.model.SelectionForest;
import viaduct.engine.runtime2.model.testing.TestWorld;
import viaduct.engine.runtime2.resolution.ParentConstructionDemandKt;
import viaduct.engine.runtime2.resolution.framework.Demand;

/** Isolates symbolic parent lookahead; schema/registry construction is outside measurement. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class ParentDemandBenchmark {
    @Param({"0", "5", "20"})
    public int depth;

    private TestWorld world;
    private Demand<SelectionForest> input;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        world = (TestWorld)Class.forName("ParentGrowthProbe").getMethod("fixture", int.class, boolean.class).invoke(null, depth, false);
        input = Demand.Companion.checked(world.selectionsFrom("fragment F on Query { root { child { field0 } } }").getSecond(), true);
    }

    @Benchmark
    public Demand<SelectionForest> lift() {
        return ParentConstructionDemandKt.liftParentConstructionDemand(input, world.getAssumptions());
    }
}
