package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.contract.ResolverResolutionObservation
import viaduct.engine.runtime2.contract.resolveAndValidateObserved
import viaduct.engine.runtime2.contract.selectionValues
import viaduct.engine.runtime2.coverage.ArgumentState
import viaduct.engine.runtime2.coverage.CoverageExecution
import viaduct.engine.runtime2.coverage.CoverageTestId
import viaduct.engine.runtime2.coverage.DeclaredOutputShape
import viaduct.engine.runtime2.coverage.OutputKind
import viaduct.engine.runtime2.coverage.ResolutionRole
import viaduct.engine.runtime2.coverage.ResolverBindingSource
import viaduct.engine.runtime2.coverage.ResolverCoverageRow
import viaduct.engine.runtime2.coverage.ResolverLocus
import viaduct.engine.runtime2.coverage.StampClass
import viaduct.engine.runtime2.coverage.coverageRows
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResolverCoverageExtractionTest : CorrectResolutionPostTestPolicy, ResolutionDispatcherResource {
    private lateinit var rows: List<ResolverCoverageRow>
    private lateinit var rowsWithoutInvocationEvidence: List<ResolverCoverageRow>

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
                "query { items { passive passiveError active child { output } } mixed(seed: 7) bad nullable(input: {values: [1, null]}) { active } }",
            )
            val execution = CoverageExecution("fixture", "E1", CoverageTestId("fixture", "schema", "R1", "Q1"))
            rows = observation.coverageRows(execution)
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
    fun `variable driven consumer occurrences are stamped`() {
        val stamped = rowsFor("Query.consume").filter { it.stampClass == StampClass.OCCURRENCE_STAMPED }
        assertEquals(3, stamped.size)
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
    fun `resolver query fragment roots contribute occurrences`(): Unit =
        runTest {
            val observation = resolveAndValidateObserved(queryFragmentWorld(), "query { value }")
            val execution = CoverageExecution("fixture", "query-fragment", CoverageTestId("fixture", "schema", "R1", "Q-query-fragment"))
            val queryRows = observation.coverageRows(execution)

            assertTrue(queryRows.any { it.fieldCoordinate == "Query.dependency" && it.resultPath.contains("queryFragment-") })
        }

    private fun rowsFor(fieldCoordinate: String): List<ResolverCoverageRow> = rows.filter { it.fieldCoordinate == fieldCoordinate }

    private fun withoutInvocationEvidence(observation: ResolverResolutionObservation): ResolverResolutionObservation =
        object : ResolverResolutionObservation by observation {
            override val appliedResolverOccurrences: Set<ResolverOccurrenceId>? = null
        }
}

private fun queryFragmentWorld(): TestWorld =
    TestWorld.fromSDL(
        schemaSDL = "type Query { value: Int! dependency: Int! }",
        fieldResolvers = { schema ->
            val value = schema.loweredSchema.requireObjectField("Query", "value")
            val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
            mapOf(
                value to
                    fieldResolverOf(
                        objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                        queryFragment = schema.fragmentFrom("fragment ValueQuery on Query { dependency }"),
                    ) { _, query, _ ->
                        query.selectionValues().getValue("dependency")
                    },
                dependency to
                    fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
            )
        },
    )

private val RUNTIME_SCHEMA =
    """
    extend type Query {
      items: [[[Item]]] @resolver(result: [[[{passive: null, passiveError: "ERROR", child: {base: 7}}, {passive: null, passiveError: "ERROR", child: {base: 7}}]]])
      mixed(seed: Int!): Int @resolver(
        of: "source { value } argument: consume(value: ${'$'}seed) field: consume(value: ${'$'}fromSource)"
        pathVars: [{name: "fromSource", path: ["source", "value"]}]
        result: 1
      )
      source: Source @resolver(result: {value: 7})
      invalidSource: Source @resolver(result: {value: "ERROR"})
      consume(value: Int): Int @resolver(result: 1)
      bad: Int @resolver(
        of: "invalidSource { value } consume(value: ${'$'}fromInvalidSource)"
        pathVars: [{name: "fromInvalidSource", path: ["invalidSource", "value"]}]
        result: 1
      )
      nullable(input: Input): Item @resolver(result: null)
    }
    input Input { values: [Int] }
    type Source { value: Int }
    type Item {
      passive: Int @resolver(result: 99)
      passiveError: Int @resolver(result: 99)
      active: Int @resolver(result: 7)
      child: Child @resolver(result: {})
    }
    type Child {
      base: Int
      output: [Int] @resolver(result: "ERROR")
    }
    """.trimIndent()
