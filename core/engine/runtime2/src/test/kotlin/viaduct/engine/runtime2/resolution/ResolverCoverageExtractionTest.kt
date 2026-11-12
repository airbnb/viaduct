package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.bootstrap.fromQueryField
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.contract.ResolverResolutionObservation
import viaduct.engine.runtime2.contract.resolveAndValidateObserved
import viaduct.engine.runtime2.coverage.ArgumentState
import viaduct.engine.runtime2.coverage.CoverageExecution
import viaduct.engine.runtime2.coverage.CoverageTestId
import viaduct.engine.runtime2.coverage.DeclaredOutputShape
import viaduct.engine.runtime2.coverage.OutputKind
import viaduct.engine.runtime2.coverage.ResolutionRole
import viaduct.engine.runtime2.coverage.ResolverBindingSource
import viaduct.engine.runtime2.coverage.ResolverCoverageCsv
import viaduct.engine.runtime2.coverage.ResolverCoverageMeasurement
import viaduct.engine.runtime2.coverage.ResolverCoverageRow
import viaduct.engine.runtime2.coverage.ResolverDependencyRow
import viaduct.engine.runtime2.coverage.ResolverLocus
import viaduct.engine.runtime2.coverage.StampClass
import viaduct.engine.runtime2.coverage.coverageRecords
import viaduct.engine.runtime2.coverage.coverageRows
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.ProviderCellOutcome
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResolverCoverageExtractionTest : CorrectResolutionPostTestPolicy, ResolutionDispatcherResource {
    private lateinit var rows: List<ResolverCoverageRow>
    private lateinit var dependencies: List<ResolverDependencyRow>
    private lateinit var rowsWithoutInvocationEvidence: List<ResolverCoverageRow>
    private lateinit var execution: CoverageExecution

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)

    @BeforeAll
    fun resolveFixture(): Unit =
        runTest {
            val world = TestWorld.fromDSL(schemaSDL = RUNTIME_SCHEMA)
            val observation = resolveAndValidateObserved(
                world,
                "query { items { passive passiveError active consumer child { output dependency } } mixed(seed: 7) mixedAgain(seed: 7) multiDependency bad nullable(input: {values: [1, null]}) { active } nullableConsumer }",
            )
            execution = CoverageExecution("fixture", "E1", CoverageTestId("fixture", "schema", "R1", "Q1"))
            val records = observation.coverageRecords(execution)
            rows = records.occurrences
            dependencies = records.dependencies
            rowsWithoutInvocationEvidence = withoutInvocationEvidence(observation).coverageRows(execution)
        }

    @Test
    fun `resolver calls are marked invoked`() {
        assertEquals(setOf(ResolutionRole.INVOKED), rowsFor("Item.active").map { it.resolutionRole }.toSet())
    }

    @Test
    fun `source supplied resolver fields are identified`() {
        assertEquals(setOf(ResolutionRole.SOURCE_SUPPLIED), rowsFor("Item.passive").map { it.resolutionRole }.toSet())
    }

    @Test
    fun `source supplied nulls retain their output kind`() {
        assertEquals(setOf(OutputKind.NULL), rowsFor("Item.passive").map { it.outputKind }.toSet())
    }

    @Test
    fun `source supplied errors retain their output kind`() {
        assertEquals(2, rowsFor("Item.passiveError").count { it.outputKind == OutputKind.ERROR })
    }

    @Test
    fun `each active list element has a separate result path`() {
        val active = rowsFor("Item.active")
        assertEquals(active.size, active.map { it.resultPath }.distinct().size)
    }

    @Test
    fun `active list elements have distinct occurrence IDs`() {
        val active = rowsFor("Item.active")
        assertEquals(active.size, active.map { it.occurrenceId }.distinct().size)
    }

    @Test
    fun `nested object fields use the other object locus`() {
        assertEquals(setOf(ResolverLocus.OTHER_OBJECT), rowsFor("Child.output").map { it.locus }.toSet())
    }

    @Test
    fun `nested object fields preserve enclosing list depth`() {
        assertEquals(setOf(3), rowsFor("Child.output").map { it.listAncestorCount }.toSet())
    }

    @Test
    fun `declared list shape is distinct from an error output`() {
        assertEquals(setOf(DeclaredOutputShape.LIST), rowsFor("Child.output").map { it.declaredOutputShape }.toSet())
    }

    @Test
    fun `resolver errors retain their output kind`() {
        assertEquals(setOf(OutputKind.ERROR), rowsFor("Child.output").map { it.outputKind }.toSet())
    }

    @Test
    fun `resolver occurrences with invalid source arguments are not invoked`() {
        assertEquals(
            setOf(ResolutionRole.ARGUMENT_ERROR_NOT_INVOKED),
            rowsFor("Query.consume").filter { it.argumentState == ArgumentState.ERROR }.map { it.resolutionRole }.toSet(),
        )
    }

    @Test
    fun `invalid source arguments retain their error state`() {
        assertEquals(1, rowsFor("Query.consume").count { it.argumentState == ArgumentState.ERROR })
    }

    @Test
    fun `missing invocation evidence produces unknown roles`() {
        val active = rowsWithoutInvocationEvidence.filter { it.fieldCoordinate == "Item.active" }
        assertEquals(setOf(ResolutionRole.UNKNOWN), active.map { it.resolutionRole }.toSet())
    }

    @Test
    fun `resolver binding sources distinguish arguments and object fields`() {
        assertEquals(
            setOf(ResolverBindingSource.ARGUMENT, ResolverBindingSource.OBJECT_FIELD),
            rowsFor("Query.mixed").single().bindingSources,
        )
    }

    @Test
    fun `provider depth includes each field in the path`() {
        assertEquals(2, rowsFor("Query.mixed").single().maxProviderPathDepth)
    }

    @Test
    fun `equal provider values keep distinct cell identities`() {
        val edges = dependenciesFor("Query.multiDependency")
        assertEquals(2, edges.map { it.providerOccurrenceId }.distinct().size)
        assertEquals(setOf("Source.value"), edges.map { it.providerFieldCoordinate }.toSet())
    }

    @Test
    fun `one provider cell can feed separate consumers`() {
        val reads = dependencies.filter {
            it.providerFieldCoordinate == "Source.value" &&
                it.consumerFieldCoordinate in setOf("Query.mixed", "Query.mixedAgain")
        }
        assertEquals(
            1 to 2,
            reads.map { it.providerOccurrenceId }.distinct().size to reads.map { it.consumerOccurrenceId }.distinct().size,
        )
    }

    @Test
    fun `equal provider values in separate list elements keep separate identities`() {
        val reads = dependenciesFor("Item.consumer")
        assertEquals(2, reads.map { it.providerOccurrenceId }.distinct().size)
    }

    @Test
    fun `nested resolver reads retain their descendant paths`() {
        assertEquals(2, dependenciesFor("Child.dependency").size)
    }

    @Test
    fun `ancestor read uses the owning object path for a scalar provider`() {
        val provider = rowsFor("Item.provider").first()
        val measurement = ResolverCoverageMeasurement(listOf(execution), rows, dependencies = dependencies)

        assertEquals(OutputKind.SCALAR, provider.outputKind)
        assertTrue(measurement.descendantReadsAncestor().occurrences > 0)
    }

    @Test
    fun `object field reads identify the containing object as their source`() {
        assertEquals(setOf(ResolverBindingSource.OBJECT_FIELD), dependenciesFor("Item.consumer").map { it.providerSource }.toSet())
    }

    @Test
    fun `query provider reads from non-resolver fields are valid dependencies`() =
        runTest {
            val world = queryProviderWorld()
            val observation = resolveAndValidateObserved(world, "query { result }")
            val execution = CoverageExecution("fixture", "query-provider", CoverageTestId("fixture", "schema", "R1", "Q-query-provider"))
            val records = observation.coverageRecords(execution)
            val measurement =
                ResolverCoverageMeasurement(
                    executions = listOf(execution),
                    rows = records.occurrences,
                    dependencies = records.dependencies,
                )

            assertEquals(setOf(ResolverBindingSource.QUERY_FIELD), records.dependencies.map { it.providerSource }.toSet())
            assertEquals(setOf("Source.value"), records.dependencies.map { it.providerFieldCoordinate }.toSet())
            assertFalse(records.occurrences.any { it.fieldCoordinate == "Source.value" })
            assertEquals(records.dependencies, measurement.dependencies)
        }

    @Test
    fun `null provider cells are retained`() {
        assertEquals(setOf(ProviderCellOutcome.NULL), dependenciesFor("Query.nullableConsumer").map { it.providerOutcome }.toSet())
    }

    @Test
    fun `error provider cells are retained`() {
        assertEquals(setOf(ProviderCellOutcome.ERROR), dependenciesFor("Query.bad").map { it.providerOutcome }.toSet())
    }

    @Test
    fun `execution coexistence does not create provider edges`() {
        assertEquals(emptyList<ResolverDependencyRow>(), dependenciesFor("Query.consume"))
    }

    @Test
    fun `variable driven consumer occurrences are stamped`() {
        val stamped = rowsFor("Query.consume").filter { it.stampClass == StampClass.OCCURRENCE_STAMPED }
        assertEquals(5, stamped.size)
    }

    @Test
    fun `stamped consumer occurrences have separate paths`() {
        val stamped = rowsFor("Query.consume").filter { it.stampClass == StampClass.OCCURRENCE_STAMPED }
        assertEquals(stamped.size, stamped.map { it.resultPath }.distinct().size)
    }

    @Test
    fun `nullable input records null arguments`() {
        assertEquals(ArgumentState.CONTAINS_NULL, rowsFor("Query.nullable").single().argumentState)
    }

    @Test
    fun `nullable resolver output records null separately from declared shape`() {
        assertEquals(OutputKind.NULL, rowsFor("Query.nullable").single().outputKind)
    }

    @Test
    fun `runtime rows survive CSV round trip`() {
        val csv = StringBuilder().also { ResolverCoverageCsv.write(rows, it) }.toString()
        assertEquals(rows, ResolverCoverageCsv.read(csv.reader()))
    }

    @Test
    fun `resolver query fragment roots contribute occurrences`(): Unit =
        runTest {
            val world = queryProviderWorld()
            val observation = resolveAndValidateObserved(world, "query { result }")
            val execution = CoverageExecution("fixture", "query-fragment", CoverageTestId("fixture", "schema", "R1", "Q-query-fragment"))
            val occurrences = observation.coverageRecords(execution).occurrences

            assertTrue(occurrences.any { it.fieldCoordinate == "Query.consume" && it.resultPath.contains("queryFragment-") })
            assertTrue(
                occurrences.any {
                    it.fieldCoordinate == "Source.passive" && it.resultPath.contains("queryFragment-") &&
                        it.resolutionRole == ResolutionRole.SOURCE_SUPPLIED
                },
                occurrences.filter { it.fieldCoordinate == "Source.passive" }.toString(),
            )
        }

    @Test
    fun `coverage extraction fails when query fragment observation is unavailable`() {
        assertThrows(IllegalStateException::class.java) {
            runTest {
                val world = queryProviderWorld()
                val selections = world.schemas.operationSelectionsFrom("query { result }")
                val operation =
                    SharedOperationContext.create(
                        world = world.assumptions,
                        resolutionObserver = ResolutionObserver.NOP,
                    )
                val result = resolve(operation, world.schema.objectOf("Query"), selections)
                val observation =
                    object : ResolverResolutionObservation {
                        override val result = result
                        override val operation = operation
                    }
                observation.coverageRecords(
                    CoverageExecution("fixture", "missing-observer", CoverageTestId("fixture", "schema", "R1", "Q1")),
                )
            }
        }
    }

    @Test
    fun `coverage IDs stay stable across repeated resolver reads`() =
        runTest {
            val world = TestWorld.fromDSL(schemaSDL = RUNTIME_SCHEMA)
            val document =
                "query { items { passive passiveError active consumer child { output dependency } } " +
                    "mixed(seed: 7) mixedAgain(seed: 7) multiDependency bad nullable(input: {values: [1, null]}) { active } nullableConsumer }"
            val execution = CoverageExecution("fixture", "stable", CoverageTestId("fixture", "schema", "R1", "Q-stable"))
            val first = resolveAndValidateObserved(world, document).coverageRecords(execution)
            val second = resolveAndValidateObserved(world, document).coverageRecords(execution)

            assertEquals(first, second)
        }

    private fun rowsFor(fieldCoordinate: String): List<ResolverCoverageRow> = rows.filter { it.fieldCoordinate == fieldCoordinate }

    private fun dependenciesFor(fieldCoordinate: String): List<ResolverDependencyRow> = dependencies.filter { it.consumerFieldCoordinate == fieldCoordinate }

    private fun withoutInvocationEvidence(observation: ResolverResolutionObservation): ResolverResolutionObservation =
        object : ResolverResolutionObservation by observation {
            override val appliedResolverOccurrences: Set<ResolverOccurrenceId>? = null
        }
}

private fun queryProviderWorld(): TestWorld {
    val fragmentSource =
        "fragment Input on Query { source { value passive } consume(value: ${'$'}fromSource) }"
    return TestWorld.fromSDL(
        schemaSDL =
            """
            type Query {
              result: Int
              source: Source
              consume(value: Int): Int
            }
            type Source { value: Int passive: Int }
            """.trimIndent(),
        fieldResolvers = { schema ->
            val result = schema.loweredSchema.requireObjectField("Query", "result")
            val fragment = schema.fragmentFrom(fragmentSource, variableField = result)
            mapOf(
                result to fieldResolverOf(
                    objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                    queryFragment = fragment,
                ) { _, query, _ -> query.outputValue("consume") },
                schema.loweredSchema.requireObjectField("Query", "source") to
                    fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                        schema.loweredSchema.objectOf("Source") {
                            "value" setTo 7
                            "passive" setTo 11
                        }
                    },
                schema.loweredSchema.requireObjectField("Source", "passive") to
                    fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Source")) { _, _ -> 99 },
                schema.loweredSchema.requireObjectField("Query", "consume") to
                    fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, arguments ->
                        arguments.fieldValues.getValue("value")
                    },
            )
        },
        variableProviders = { schema ->
            val result = schema.loweredSchema.requireObjectField("Query", "result")
            mapOf(
                Arguments.Variable.of(result, "fromSource") to
                    schema.fromQueryField(fragmentSource, listOf("source", "value"), result),
            )
        },
    )
}

private val RUNTIME_SCHEMA =
    """
    extend type Query {
      items: [[[Item]]] @resolver(script: "[[[{'passive': null, 'passiveError': error, 'child': {'base': 7}}, {'passive': null, 'passiveError': error, 'child': {'base': 7}}]]]")
      mixed(seed: Int!): Int @resolver(
        of: "source { value } argument: consume(value: ${'$'}seed) field: consume(value: ${'$'}fromSource)"
        pathVars: [{name: "fromSource", path: ["source", "value"]}]
        script: "1"
      )
      mixedAgain(seed: Int!): Int @resolver(
        of: "source { value } argument: consume(value: ${'$'}fromSource)"
        pathVars: [{name: "fromSource", path: ["source", "value"]}]
        script: "1"
      )
      multiDependency: Int @resolver(
        of: "source { value } otherSource { value } argument: consumePair(first: ${'$'}fromSource, second: ${'$'}fromOther)"
        pathVars: [{name: "fromSource", path: ["source", "value"]}, {name: "fromOther", path: ["otherSource", "value"]}]
        script: "1"
      )
      source: Source @resolver(script: "{'value': 7}")
      otherSource: Source @resolver(script: "{'value': 7}")
      invalidSource: Source @resolver(script: "{'value': error}")
      nullableSource: Source @resolver(script: "{'value': null}")
      consume(value: Int): Int @resolver(script: "1")
      consumePair(first: Int, second: Int): Int @resolver(script: "1")
      bad: Int @resolver(
        of: "invalidSource { value } consume(value: ${'$'}fromInvalidSource)"
        pathVars: [{name: "fromInvalidSource", path: ["invalidSource", "value"]}]
        script: "1"
      )
      nullableConsumer: Int @resolver(
        of: "nullableSource { value } argument: consume(value: ${'$'}fromNullable)"
        pathVars: [{name: "fromNullable", path: ["nullableSource", "value"]}]
        script: "1"
      )
      nullable(input: Input): Item @resolver(script: "null")
    }
    input Input { values: [Int] }
    type Source { value: Int }
    type Item {
      passive: Int @resolver(script: "99")
      passiveError: Int @resolver(script: "99")
      active: Int @resolver(script: "7")
      consumer: Int @resolver(
        of: "provider argument: consume(value: ${'$'}fromProvider)"
        pathVars: [{name: "fromProvider", path: ["provider"]}]
        script: "1"
      )
      provider: Int @resolver(script: "7")
      consume(value: Int): Int @resolver(script: "1")
      child: Child @resolver(script: "{:}")
    }
    type Child {
      base: Int
      output: [Int] @resolver(script: "error")
      dependency: Int @resolver(
        of: "provider argument: consume(value: ${'$'}fromProvider)"
        pathVars: [{name: "fromProvider", path: ["provider"]}]
        script: "1"
      )
      provider: Int @resolver(script: "7")
      consume(value: Int): Int @resolver(script: "1")
    }
    """.trimIndent()
