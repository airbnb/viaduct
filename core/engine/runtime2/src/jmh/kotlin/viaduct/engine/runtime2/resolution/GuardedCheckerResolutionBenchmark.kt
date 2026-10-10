package viaduct.engine.runtime2.resolution

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Timeout
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/** Executes a real query whose transitive checker demand accumulates guarded resolver paths. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Timeout(time = 10, timeUnit = TimeUnit.MINUTES)
open class GuardedCheckerResolutionBenchmark {
    @JvmField
    @Param("3", "6", "9", "12")
    var depth: Int = 0

    private lateinit var dispatcher: ExecutorCoroutineDispatcher
    private lateinit var world: Assumptions
    private lateinit var selections: SelectionForest

    @Setup(Level.Trial)
    fun prepareTrial() {
        val fixture = guardedCheckerWorld(depth)
        world = fixture.assumptions
        selections = fixture.schemas.fragmentFrom("fragment Benchmark on Query { field0 }").subselections
        dispatcher = ResolutionDispatcherFactory.create(configuredResolutionThreadCount())
    }

    @TearDown(Level.Trial)
    fun closeTrial() {
        dispatcher.close()
    }

    @Benchmark
    fun execute(blackhole: Blackhole) {
        val operation = SharedOperationContext.create(world)
        blackhole.consume(
            operation.resolve(
                selections = selections,
                coroutineContext = dispatcher,
            ),
        )
    }
}

private fun guardedCheckerWorld(depth: Int): TestWorld {
    require(depth > 0) { "Guarded checker benchmark depth must be positive" }
    return TestWorld.fromSDL(
        schemaSDL =
            "type Query { audit: Int " +
                (0..depth).joinToString(" ") { "field$it: Int" } +
                " }",
        selectiveResolvers = true,
        fieldResolvers = { schema ->
            (0..depth).associate { index ->
                val field = schema.loweredSchema.requireObjectField("Query", "field$index")
                val empty = schema.loweredSchema.emptyFragmentOf("Query")
                val resolver =
                    fieldResolverOf(
                        objectFragment = empty,
                        queryFragment =
                            if (index == depth) {
                                empty
                            } else {
                                schema.fragmentFrom(
                                    "fragment F on Query { " +
                                        "left: field${index + 1} @include(if: \$a) " +
                                        "right: field${index + 1} @skip(if: \$b) " +
                                        "}",
                                    variableField = field,
                                )
                            },
                    ) { _, _, _ -> 1 }
                field to
                    if (index == depth) {
                        resolver
                    } else {
                        resolver.withVariablesProvider(setOf("a", "b")) {
                            mapOf("a" to true, "b" to false)
                        }
                    }
            }
        },
        fieldCheckers = { schema ->
            val checked = schema.loweredSchema.requireObjectField("Query", "field$depth")
            val inputs =
                ResolverFragmentTemplates(
                    schema.fragmentFrom("fragment CheckerInput on Query { audit }").materializeSelections,
                    materializeSelectionForestOf(),
                )
            mapOf(
                checked to
                    FieldCheckerResolver.of(
                        checked,
                        schema.loweredSchema.requireQueryTypeDef(),
                        mapOf("input" to inputs),
                    ) { _, _, _ -> CheckerResult.Success },
            )
        },
    )
}
