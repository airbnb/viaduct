package viaduct.engine.runtime.execution

import graphql.ExecutionResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.future.await
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.graphql.ExplicitNullValueWeight
import viaduct.arbitrary.graphql.arbRuntimeWiring
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createExecutionInput
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createGJGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createSchema
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createViaductGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 3)
@ExperimentalCoroutinesApi
open class ExecutionBenchmark {
    private class Fixture(data: TestData, type: Type) {
        val wiring = arbRuntimeWiring(
            data.sdl,
            seed = 0,
            // configure the arb wiring to not return null values,
            // ensuring that we get interesting responses
            Config.default + (ExplicitNullValueWeight to 0.0)
        )
        val schema = createSchema(data.sdl, wiring)
        val gql = when (type) {
            Type.GJ -> createGJGraphQL(schema)
            Type.MODERN -> createViaductGraphQL(schema)
        }
        val input = createExecutionInput(schema, data.query, data.variables)

        fun execute(): ExecutionResult =
            runExecutionTest {
                val modernResult = gql.executeAsync(input).await()
                assert(modernResult.errors.isEmpty())
                modernResult
            }
    }

    enum class Type { GJ, MODERN }

    @Param("GJ", "MODERN")
    private var type: Type? = null

    private lateinit var simple: Fixture
    private lateinit var extraLarge2: Fixture

    @Setup
    fun setup() {
        simple = Fixture(TestData("type Query { x:Int }", "{ x }"), type!!)
        extraLarge2 = Fixture(TestData.loadFromResources("extra-large-2"), type!!)
    }

    @Benchmark
    fun simple(blackHole: Blackhole) {
        blackHole.consume(simple.execute())
    }

    @Benchmark
    fun extraLarge2(blackHole: Blackhole) {
        blackHole.consume(extraLarge2.execute())
    }
}
