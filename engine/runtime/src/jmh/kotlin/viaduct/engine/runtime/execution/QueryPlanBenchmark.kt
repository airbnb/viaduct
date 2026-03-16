@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import viaduct.engine.api.parse.CachedDocumentParser.parseDocument
import viaduct.engine.runtime.RequiredSelectionSetRegistry
import viaduct.engine.runtime.createSchema

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
open class QueryPlanBenchmark {
    private class Fixture(data: TestData) {
        val schema = createSchema(data.sdl)
        val document = parseDocument(data.query)

        val parameters = QueryPlan.Parameters(
            data.query,
            schema,
            RequiredSelectionSetRegistry.Empty,
            // passing false here, as RSS registry is empty, plus query plan cache is turned off.
            // So it makes no difference whether passing true or false.
            executeAccessChecksInModstrat = false
        )

        fun toQueryPlan() {
            runBlocking {
                QueryPlanFactory.Default.build(parameters, document)
            }
        }
    }

    private lateinit var simple: Fixture
    private lateinit var manyFragments1: Fixture
    private lateinit var extraLarge1: Fixture
    private lateinit var extraLarge2: Fixture
    private lateinit var extraLarge3: Fixture

    @Setup
    fun setup() {
        simple = Fixture(
            TestData(
                "type Query { simpleField: String }",
                "query SimpleQuery { simpleField } "
            )
        )

        manyFragments1 = Fixture(TestData.loadFromResources("many-fragments-1"))
        extraLarge1 = Fixture(TestData.loadFromResources("extra-large-1"))
        extraLarge2 = Fixture(TestData.loadFromResources("extra-large-2"))
        extraLarge3 = Fixture(TestData.loadFromResources("extra-large-3"))
    }

    @Benchmark
    fun `simple`(blackhole: Blackhole) {
        val plan = simple.toQueryPlan()
        blackhole.consume(plan)
    }

    @Benchmark
    fun manyFragments1(blackhole: Blackhole) {
        val plan = manyFragments1.toQueryPlan()
        blackhole.consume(plan)
    }

    /**
     * This benchmark uses the extraLarge fixture taken from graphql-java. GJ uses this
     * fixture to benchmark creation of ExecutableNormalizedOperations, a concept similar
     * to QueryPlan.
     *
     * The GJ benchmark can be run from a clone of the GJ source:
     * ```
     * $ ./gradlew jmhJar
     * $ java -jar build/libs/graphql-java-0.0.0-master-SNAPSHOT-jmh.jar benchmark.ENFExtraLargeBenchmark
     * ```
     *
     * See: https://github.com/graphql-java/graphql-java/blob/3d193c348d05bf6c03ab12d212bbd52841f21be2/src/test/java/benchmark/ENFExtraLargeBenchmark.java
     */
    @Benchmark
    fun extraLarge1(blackhole: Blackhole) {
        val plan = extraLarge1.toQueryPlan()
        blackhole.consume(plan)
    }

    @Benchmark
    fun extraLarge2(blackhole: Blackhole) {
        val plan = extraLarge2.toQueryPlan()
        blackhole.consume(plan)
    }

    @Benchmark
    fun extraLarge3(blackhole: Blackhole) {
        val plan = extraLarge3.toQueryPlan()
        blackhole.consume(plan)
    }
}
