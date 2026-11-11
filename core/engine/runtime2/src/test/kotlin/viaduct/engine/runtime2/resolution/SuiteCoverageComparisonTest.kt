package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.coverage.CandidateCoverage
import viaduct.engine.runtime2.coverage.CoverageCandidate
import viaduct.engine.runtime2.coverage.CoverageSupport
import viaduct.engine.runtime2.coverage.CoverageVerdict
import viaduct.engine.runtime2.coverage.MetricComparison
import viaduct.engine.runtime2.coverage.ResolverCoverageMeasurement
import viaduct.engine.runtime2.coverage.ResolverTestSuiteComparer
import viaduct.engine.runtime2.coverage.SuiteCoverageComparison
import viaduct.engine.runtime2.coverage.SuiteCoverageComparisonPage
import viaduct.engine.runtime2.coverage.forBatch
import viaduct.engine.runtime2.coverage.measureCoverage
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.propertytest.PropertyTestJson
import viaduct.engine.runtime2.propertytest.ResolverTestSuite
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SuiteCoverageComparisonTest : CorrectResolutionPostTestPolicy, ResolutionDispatcherResource {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)

    private val definition = ResolverTestSuiteDefinition.ROOT_FIELD_REFERENCE
    private val suite = ResolverTestSuite.load(definition)
    private val stored = CoverageCandidate.Stored(suite)
    private lateinit var measurement: ResolverCoverageMeasurement
    private lateinit var coverage: CandidateCoverage

    @BeforeAll
    fun measureStoredSuite(): Unit =
        runTest {
            measurement = measureCoverage(stored, runId = "run")
            coverage = CandidateCoverage.of("a", 0, measurement)
        }

    @Test
    fun `regenerating a suite from its own definition measures exactly what replaying it does`(): Unit =
        runTest {
            val generated = measureCoverage(CoverageCandidate.Generated(definition), runId = "run")

            assertEquals(measurement, generated)
        }

    @Test
    fun `a prepared regenerated candidate replays the batches it generated`(): Unit =
        runTest {
            val candidate = CoverageCandidate.Generated(definition)
            candidate.prepare()

            assertEquals(measurement, measureCoverage(candidate, runId = "run"))
        }

    @Test
    fun `a verdict between candidates of different cost is flagged as raw coverage`() {
        val smaller = CandidateCoverage.of("b", 0, measurement.forBatch(suite.batches.first().let { "${definition.id}/${it.id}" }))
        val comparison = SuiteCoverageComparison(coverage, smaller)

        val rendered = StringBuilder().also { comparison.render(it) }.toString()

        assertFalse(comparison.sameCost)
        assertTrue(SuiteCoverageComparison(coverage, coverage).sameCost)
        assertTrue("warning: A and B differ in cost (${coverage.cases} vs ${smaller.cases} cases" in rendered, rendered)
    }

    @Test
    fun `batch reports partition the suite's executions and evidence`() {
        assertEquals(suite.batches.size, coverage.batchReports.size)
        assertEquals(measurement.executions.size, coverage.batchReports.values.sumOf { it.executions.size })
        coverage.report.metrics.forEachIndexed { index, metric ->
            assertEquals(metric.support.occurrences, coverage.batchReports.values.sumOf { it.metrics[index].support.occurrences }, metric.id)
        }
    }

    @Test
    fun `suite verdict is equal for identical candidates, one-sided when one side dominates, and mixed otherwise`() {
        val betterFirst = coverage.bumped(0)
        val betterSecond = coverage.bumped(1)

        assertEquals(CoverageVerdict.EQUAL, SuiteCoverageComparison(coverage, coverage).verdict)
        assertEquals(CoverageVerdict.RIGHT_DOMINATES, SuiteCoverageComparison(coverage, betterFirst).verdict)
        assertEquals(CoverageVerdict.LEFT_DOMINATES, SuiteCoverageComparison(betterFirst, coverage).verdict)
        assertEquals(CoverageVerdict.MIXED, SuiteCoverageComparison(betterSecond, betterFirst).verdict)
        val rendered = StringBuilder().also { SuiteCoverageComparison(coverage, betterFirst).render(it) }.toString()
        assertTrue("verdict (raw coverage): B" in rendered, rendered)
        assertFalse("warning:" in rendered, rendered)
    }

    @Test
    fun `a metric is won only by a side no worse on both distinct tests and batches`() {
        fun support(
            tests: Int,
            batches: Int,
            occurrences: Int = tests,
        ) = CoverageSupport(occurrences, tests, tests, tests, batches, batches)

        assertEquals(CoverageVerdict.EQUAL, MetricComparison("m", support(4, 2), support(4, 2)).verdict)
        assertEquals(CoverageVerdict.RIGHT_DOMINATES, MetricComparison("m", support(4, 2), support(9, 2)).verdict)
        assertEquals(CoverageVerdict.LEFT_DOMINATES, MetricComparison("m", support(4, 3), support(4, 2)).verdict)
        assertEquals(CoverageVerdict.MIXED, MetricComparison("m", support(9, 1), support(4, 2)).verdict)
    }

    @Test
    fun `occurrence volume alone does not win a metric`() {
        val few = CoverageSupport(occurrences = 3, executions = 2, tests = 2, registries = 2, batches = 1, schemas = 1)
        val many = few.copy(occurrences = 300)

        assertEquals(CoverageVerdict.EQUAL, MetricComparison("m", few, many).verdict)
    }

    @Test
    fun `the comparison page draws a batch both candidates contain once`(): Unit =
        runTest {
            val page = SuiteCoverageComparisonPage.render(SuiteCoverageComparison(coverage, coverage), stored.batches(), CoverageCandidate.Generated(definition).batches())

            val batches = page.embeddedBatches()
            assertEquals(suite.batches.map { it.id }, batches.map { it["id"] })
            assertEquals(setOf("both"), batches.map { it["side"] }.toSet())
            assertEquals(measurement.executions.size, batches.sumOf { (it["cases"] as List<*>).size })
        }

    @Test
    fun `the comparison page keeps each candidate's own batches and cases`(): Unit =
        runTest {
            val candidate = CoverageCandidate.Generated(definition, seed = 2, counts = TestCaseCount(2, 2))
            val comparison = ResolverTestSuiteComparer.compare(stored, candidate)

            val page = SuiteCoverageComparisonPage.render(comparison, stored.batches(), candidate.batches())

            val sides = page.embeddedBatches().groupBy({ it["side"] }, { (it["cases"] as List<*>).size })
            assertEquals(mapOf("a" to suite.batches.size, "b" to 2), sides.mapValues { it.value.size })
            assertEquals(comparison.left.cases, sides.getValue("a").sum())
            assertEquals(comparison.right.cases, sides.getValue("b").sum())
        }

    @Test
    fun `candidate specs name a stored suite or regenerate it with seed and size overrides`() {
        assertInstanceOf(CoverageCandidate.Stored::class.java, CoverageCandidate.parse(definition.id))
        val generated = assertInstanceOf(CoverageCandidate.Generated::class.java, CoverageCandidate.parse("${definition.id}@seed=7,size=2:3"))
        assertEquals(7L, generated.seed)
        assertEquals(TestCaseCount(2, 3), generated.counts)
        assertEquals(definition.counts, (CoverageCandidate.parse("${definition.id}@seed=7") as CoverageCandidate.Generated).counts)
        assertThrows<IllegalArgumentException> { CoverageCandidate.parse("no-such-suite") }
        assertThrows<IllegalArgumentException> { CoverageCandidate.parse("${definition.id}@colour=7") }
        assertThrows<IllegalArgumentException> { CoverageCandidate.parse("${definition.id}@seed=x") }
    }

    @Test
    fun `the comparer measures a stored suite against a regenerated candidate`() {
        val candidate = CoverageCandidate.Generated(definition, seed = 2, counts = TestCaseCount(2, 2))

        val comparison = ResolverTestSuiteComparer.compare(stored, candidate)

        assertEquals(definition.id, comparison.left.label)
        assertEquals("${definition.id}@seed=2,size=2:2", comparison.right.label)
        assertEquals(2, comparison.right.batches)
        assertTrue(comparison.right.cases in 1..4)
    }
}

private fun CandidateCoverage.bumped(metricIndex: Int): CandidateCoverage =
    copy(
        report =
            report.copy(
                metrics =
                    report.metrics.mapIndexed { index, metric ->
                        if (index != metricIndex) {
                            metric
                        } else {
                            metric.copy(support = metric.support.copy(tests = metric.support.tests + 1, batches = metric.support.batches + 1))
                        }
                    },
            ),
    )

private fun String.embeddedBatches(): List<Map<*, *>> {
    val json = substringAfter("<script type=\"application/json\" id=\"data\">").substringBefore("</script>")
    return (PropertyTestJson.read<Map<String, Any?>>(json.byteInputStream())["batches"] as List<*>).map { it as Map<*, *> }
}
