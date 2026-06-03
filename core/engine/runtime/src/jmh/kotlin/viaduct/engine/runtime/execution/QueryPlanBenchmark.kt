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
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.parse.CachedDocumentParser.parseDocument
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.RequiredSelectionSetRegistry
import viaduct.engine.runtime.createSchema

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
// The 800-link chained RSS fixture intentionally drives deep recursive plan construction.
@Fork(1, jvmArgsAppend = ["-Xss16m"])
@Warmup(iterations = 3)
@Measurement(iterations = 5)
open class QueryPlanBenchmark {
    private class Fixture(
        data: TestData,
        registry: RequiredSelectionSetRegistry = RequiredSelectionSetRegistry.Empty,
    ) {
        val schema = createSchema(data.sdl)
        val document = parseDocument(data.query)

        val parameters = QueryPlan.Parameters(
            data.query,
            schema,
            registry,
        )

        fun toQueryPlan(): QueryPlan =
            runBlocking {
                QueryPlanFactory.Default.build(parameters, document)
            }
    }

    @State(Scope.Benchmark)
    open class WideRssFixture {
        // Client-query selections plus selections inside eager RSS child plans.
        @Param("200", "800")
        var logicalSelectionCount: Int = 0

        private lateinit var fixture: Fixture

        @Setup
        fun setup() {
            fixture = createWideRssFixture(logicalSelectionCount)
        }

        fun toQueryPlan(): QueryPlan = fixture.toQueryPlan()
    }

    @State(Scope.Benchmark)
    open class DeepRssFixture {
        // Client-query selections plus selections inside eager RSS child plans.
        @Param("200", "800")
        var logicalSelectionCount: Int = 0

        private lateinit var fixture: Fixture

        @Setup
        fun setup() {
            fixture = createDeepRssFixture(logicalSelectionCount)
        }

        fun toQueryPlan(): QueryPlan = fixture.toQueryPlan()
    }

    @State(Scope.Benchmark)
    open class ChainedRssFixture {
        // Client-query selections plus selections inside transitive RSS child plans.
        @Param("200", "800")
        var logicalSelectionCount: Int = 0

        private lateinit var fixture: Fixture

        @Setup
        fun setup() {
            fixture = createChainedRssFixture(logicalSelectionCount)
        }

        fun toQueryPlan(): QueryPlan = fixture.toQueryPlan()
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

    @Benchmark
    fun wideRssChildren(
        state: WideRssFixture,
        blackhole: Blackhole
    ) {
        blackhole.consume(state.toQueryPlan())
    }

    @Benchmark
    fun deepRssChildren(
        state: DeepRssFixture,
        blackhole: Blackhole
    ) {
        blackhole.consume(state.toQueryPlan())
    }

    @Benchmark
    fun chainedRssChildren(
        state: ChainedRssFixture,
        blackhole: Blackhole
    ) {
        blackhole.consume(state.toQueryPlan())
    }

    private companion object {
        fun createWideRssFixture(logicalSelectionCount: Int): Fixture {
            require(logicalSelectionCount > 0) {
                "logicalSelectionCount must be positive"
            }
            require(logicalSelectionCount % 2 == 0) {
                "wideRssChildren requires an even logicalSelectionCount"
            }

            // Each selected field has one required sibling selection.
            val fieldCount = logicalSelectionCount / 2
            val fields = (0 until fieldCount).map { "field$it" }
            val rssFields = (0 until fieldCount).map { "rss$it" }
            val sdl = buildString {
                appendLine("type Query {")
                fields.forEach { appendLine("  $it: String") }
                rssFields.forEach { appendLine("  $it: String") }
                appendLine("}")
            }
            val query = fields.joinToString(
                separator = "\n  ",
                prefix = "query WideRssChildren {\n  ",
                postfix = "\n}"
            )
            val fieldResolverEntries = fields
                .zip(rssFields)
                .associate { (field, rssField) ->
                    ("Query" to field) to listOf(requiredSelectionSet("Query", rssField))
                }

            return Fixture(
                TestData(sdl, query),
                StaticRequiredSelectionSetRegistry(fieldResolverEntries)
            )
        }

        fun createDeepRssFixture(logicalSelectionCount: Int): Fixture {
            require(logicalSelectionCount >= 2) {
                "logicalSelectionCount must be at least 2"
            }
            require(logicalSelectionCount % 2 == 0) {
                "deepRssChildren requires an even logicalSelectionCount"
            }

            // The query contributes level0 + child fields + leaf; RSS contributes one id for
            // each child field.
            val childDepth = (logicalSelectionCount / 2) - 1
            val sdl = buildString {
                appendLine("type Query {")
                appendLine("  level0: Level0")
                appendLine("}")

                for (level in 0 until childDepth) {
                    appendLine("type Level$level {")
                    appendLine("  id: ID")
                    appendLine("  child: Level${level + 1}")
                    appendLine("}")
                }

                appendLine("type Level$childDepth {")
                appendLine("  id: ID")
                appendLine("  leaf: String")
                appendLine("}")
            }
            val query = "query DeepRssChildren {\n  level0 {\n${nestedChildSelection(childDepth, indent = 4)}\n  }\n}"
            val fieldResolverEntries = buildMap {
                for (level in 0 until childDepth) {
                    put(("Level$level" to "child"), listOf(requiredSelectionSet("Level$level", "id")))
                }
            }

            return Fixture(
                TestData(sdl, query),
                StaticRequiredSelectionSetRegistry(fieldResolverEntries)
            )
        }

        fun createChainedRssFixture(logicalSelectionCount: Int): Fixture {
            require(logicalSelectionCount >= 2) {
                "logicalSelectionCount must be at least 2"
            }

            // The query contributes field0; each field before the last contributes one RSS
            // selecting the next field.
            val fields = (0 until logicalSelectionCount).map { "field$it" }
            val sdl = buildString {
                appendLine("type Query {")
                fields.forEach { appendLine("  $it: String") }
                appendLine("}")
            }
            val query = "query ChainedRssChildren {\n  field0\n}"
            val fieldResolverEntries = buildMap {
                for (fieldIndex in 0 until fields.lastIndex) {
                    put(
                        "Query" to fields[fieldIndex],
                        listOf(requiredSelectionSet("Query", fields[fieldIndex + 1]))
                    )
                }
            }

            return Fixture(
                TestData(sdl, query),
                StaticRequiredSelectionSetRegistry(fieldResolverEntries)
            )
        }

        private fun nestedChildSelection(
            depth: Int,
            indent: Int
        ): String {
            val spaces = " ".repeat(indent)
            return if (depth == 0) {
                "${spaces}leaf"
            } else {
                """
                |${spaces}child {
                |${nestedChildSelection(depth - 1, indent + 2)}
                |$spaces}
                """.trimMargin()
            }
        }

        private fun requiredSelectionSet(
            typeName: String,
            selections: String
        ): RequiredSelectionSet =
            RequiredSelectionSet(
                selections = SelectionsParser.parse(typeName, selections),
                variablesResolvers = emptyList(),
                forChecker = false
            )
    }

    private class StaticRequiredSelectionSetRegistry(
        private val fieldResolverEntries: Map<Pair<String, String>, List<RequiredSelectionSet>>
    ) : RequiredSelectionSetRegistry {
        override fun getFieldResolverRequiredSelectionSets(
            typeName: String,
            fieldName: String
        ): List<RequiredSelectionSet> = fieldResolverEntries[typeName to fieldName] ?: emptyList()

        override fun getFieldCheckerRequiredSelectionSets(
            typeName: String,
            fieldName: String
        ): List<RequiredSelectionSet> = emptyList()

        override fun getTypeCheckerRequiredSelectionSets(typeName: String): List<RequiredSelectionSet> = emptyList()
    }
}
