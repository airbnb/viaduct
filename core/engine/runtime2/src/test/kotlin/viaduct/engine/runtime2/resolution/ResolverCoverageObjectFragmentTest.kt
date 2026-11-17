package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.contract.resolveAndValidateObserved
import viaduct.engine.runtime2.coverage.CoverageExecution
import viaduct.engine.runtime2.coverage.CoverageTestId
import viaduct.engine.runtime2.coverage.ObjectFragmentShape
import viaduct.engine.runtime2.coverage.ResolutionRole
import viaduct.engine.runtime2.coverage.ResolverCoverageCsv
import viaduct.engine.runtime2.coverage.ResolverCoverageMeasurement
import viaduct.engine.runtime2.coverage.ResolverCoverageRequirement
import viaduct.engine.runtime2.coverage.ResolverCoverageRequirementKind
import viaduct.engine.runtime2.coverage.ResolverCoverageRow
import viaduct.engine.runtime2.coverage.ResolverCoverageScoringCriteria
import viaduct.engine.runtime2.coverage.analyze
import viaduct.engine.runtime2.coverage.compareCandidate
import viaduct.engine.runtime2.coverage.coverageRecords
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResolverCoverageObjectFragmentTest : CorrectResolutionPostTestPolicy, ResolutionDispatcherResource {
    private lateinit var measurement: ResolverCoverageMeasurement

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)

    @BeforeAll
    fun resolveFixture(): Unit =
        runTest {
            val world = TestWorld.fromDSL(schemaSDL = SCHEMA)
            val observation =
                resolveAndValidateObserved(
                    world,
                    "query { holder { plain readsFailing nested listed conditioned throughInterface skipsFailing kid { viaParent } } holders { readsFailing } }",
                )
            val execution = CoverageExecution("fixture", "E1", CoverageTestId("fixture/b001", "schema", "R1", "Q1"))
            val records = observation.coverageRecords(execution)
            measurement = ResolverCoverageMeasurement(listOf(execution), records.occurrences, dependencies = records.dependencies)
        }

    @Test
    fun `a fragment of plain fields reads no resolver field`() {
        assertEquals(ObjectFragmentShape(1, 1, 0, 0, 0, 0, 0, 0), shapeOf("Holder.plain"))
    }

    @Test
    fun `a fragment of failing resolver fields counts each failed read`() {
        assertEquals(ObjectFragmentShape(3, 1, 2, 0, 0, 0, 0, 2), shapeOf("Holder.readsFailing"))
    }

    @Test
    fun `a nested resolver field is counted below the first level`() {
        assertEquals(ObjectFragmentShape(2, 2, 2, 1, 0, 0, 0, 0), shapeOf("Holder.nested"))
    }

    @Test
    fun `a fragment beneath a list field counts a list traversal`() {
        assertEquals(ObjectFragmentShape(2, 2, 2, 1, 1, 0, 0, 0), shapeOf("Holder.listed"))
    }

    @Test
    fun `a fragment narrowing an interface counts a type-conditioned selection`() {
        assertEquals(ObjectFragmentShape(2, 2, 0, 0, 0, 1, 0, 0), shapeOf("Holder.conditioned"))
    }

    @Test
    fun `a field selected through an interface is resolver-backed when a concrete branch has a resolver`() {
        assertEquals(ObjectFragmentShape(2, 2, 1, 1, 0, 0, 0, 0), shapeOf("Holder.throughInterface"))
    }

    @Test
    fun `a skipped fragment field is not a failed read even when the query fetches it`() {
        assertEquals(0, shapeOf("Holder.skipsFailing").failedResolverReads)
        assertEquals(2, shapeOf("Holder.readsFailing").failedResolverReads)
    }

    @Test
    fun `a fragment through a parent field counts a parent traversal`() {
        assertEquals(1, shapeOf("Kid.viaParent").parentTraversals)
    }

    @Test
    fun `fragment interactions are obligations and fragment shape traits are taxonomy attributes`() {
        val signature = measurement.analyze().signatures.single()

        assertEquals(
            setOf("object_fragment_resolver_field_in_list_element", "object_fragment_reads_failed_resolver_field"),
            signature.obligations.filter { it.startsWith("object_fragment") }.toSet(),
        )
        assertEquals(
            setOf(
                "object_fragment:reads_resolver_field",
                "object_fragment:reads_nested_resolver_field",
                "object_fragment:crosses_list",
                "object_fragment:type_conditioned",
                "object_fragment:traverses_parent",
            ),
            signature.attributes.filter { it.startsWith("object_fragment:") }.toSet(),
        )
    }

    @Test
    fun `a fragment trait counts only invoked occurrences whose shape has it`() {
        val traverses = measurement.report().attributes.single { it.id == "object_fragment:traverses_parent" }

        assertEquals(1, traverses.support.occurrences)
        assertEquals(1, invoked("Kid.viaParent").size)
    }

    @Test
    fun `an unobserved fragment depth can be required and scores zero`() {
        val report = measurement.report()
        val requirement = ResolverCoverageRequirement(ResolverCoverageRequirementKind.ATTRIBUTE, "object_fragment_depth:9", 1)

        val score = report.compareCandidate(report, ResolverCoverageScoringCriteria(setOf(requirement))).candidateScore

        assertEquals(0, score.supportFor(requirement))
        assertEquals(setOf(requirement), score.unmetRequirements)
    }

    @Test
    fun `object-fragment metric support matches case evidence`() {
        val report = measurement.report()
        val supported = report.metrics.filter { it.id.startsWith("object_fragment_") && it.support.tests > 0 }.map { it.id }.toSet()

        assertEquals(report.analysis.signatures.single().obligations.filter { it.startsWith("object_fragment_") }.toSet(), supported)
        assertEquals(
            setOf("object_fragment_depth:0", "object_fragment_depth:1", "object_fragment_depth:2"),
            report.attributes.filter { it.id.startsWith("object_fragment_depth:") }.map { it.id }.toSet(),
        )
    }

    @Test
    fun `object-fragment shapes round trip through CSV`() {
        val csv = StringBuilder().also { ResolverCoverageCsv.write(measurement.rows, it) }.toString()

        assertEquals(measurement.rows, ResolverCoverageCsv.read(csv.reader()))
    }

    private fun shapeOf(coordinate: String): ObjectFragmentShape = invoked(coordinate).map(ResolverCoverageRow::objectFragment).distinct().single()

    private fun invoked(coordinate: String): List<ResolverCoverageRow> =
        measurement.rows.filter { it.fieldCoordinate == coordinate && it.resolutionRole == ResolutionRole.INVOKED }.also {
            check(it.isNotEmpty()) { "No invoked $coordinate occurrence" }
        }
}

private val SCHEMA =
    """
    extend type Query {
      holder: Holder @resolver(script: "{'base': 1, 'pet': {'__typename': 'Dog', 'name': 1, 'bark': 1}}")
      holders: [Holder] @resolver(script: "[{'base': 1}, {'base': 2}]")
    }
    interface Pet { name: Int }
    type Dog implements Pet { name: Int @resolver(script: "1") bark: Int }
    type Cat implements Pet { name: Int }
    type Holder {
      base: Int
      pet: Pet
      failing: Int @resolver(script: "error")
      nothing: Int @resolver(script: "null")
      plain: Int @resolver(of: "base", script: "1")
      readsFailing: Int @resolver(of: "base failing nothing", script: "1")
      child: Child @resolver(script: "{:}")
      children: [Child] @resolver(script: "[{:}, {:}]")
      nested: Int @resolver(of: "child { value }", script: "1")
      listed: Int @resolver(of: "children { value }", script: "1")
      conditioned: Int @resolver(of: "pet { ... on Dog { bark } }", script: "1")
      throughInterface: Int @resolver(of: "pet { name }", script: "1")
      skipsFailing: Int @resolver(of: "base failing @skip(if: true)", script: "1")
      kid: Kid @resolver(script: "{:}")
    }
    type Kid {
      up: Holder @parent
      viaParent: Int @resolver(of: "up { base }", script: "1")
    }
    type Child {
      value: Int @resolver(script: "3")
    }
    """.trimIndent()
