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
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.ErrorValueWeight
import viaduct.engine.runtime2.arbitrary.FieldArgumentWeight
import viaduct.engine.runtime2.arbitrary.ListsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromArgumentVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariableCount
import viaduct.engine.runtime2.arbitrary.ResolverVariablesEnabled
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.coverage.CandidateCoverage
import viaduct.engine.runtime2.coverage.ConfigSweepVariant
import viaduct.engine.runtime2.coverage.CoverageCandidate
import viaduct.engine.runtime2.coverage.CoverageSupport
import viaduct.engine.runtime2.coverage.CoverageVerdict
import viaduct.engine.runtime2.coverage.MetricComparison
import viaduct.engine.runtime2.coverage.ResolverCoverageMeasurement
import viaduct.engine.runtime2.coverage.ResolverTestSuiteComparer
import viaduct.engine.runtime2.coverage.SuiteCoverageComparison
import viaduct.engine.runtime2.coverage.SuiteCoverageComparisonPage
import viaduct.engine.runtime2.coverage.forBatch
import viaduct.engine.runtime2.coverage.inapplicableObligationIds
import viaduct.engine.runtime2.coverage.interactionVectorObservations
import viaduct.engine.runtime2.coverage.measureCoverage
import viaduct.engine.runtime2.coverage.render
import viaduct.engine.runtime2.coverage.resolverCoverageMetricCodes
import viaduct.engine.runtime2.coverage.sweepConfigs
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
    fun `D0 counts distinct RFC-271 interaction vectors across the suite and each batch`() {
        val observations = measurement.interactionVectorObservations()
        val observationsByBatch = observations.groupBy { it.test.batchId }
        val byBatch = measurement.executions.map { it.test.batchId }.distinct().associateWith { batchId ->
            observationsByBatch[batchId].orEmpty().map { it.vector }.distinct().size
        }

        assertEquals(observations.map { it.vector }.distinct().size, coverage.vectorD0)
        assertEquals(byBatch, coverage.batchVectorD0)
        assertTrue(coverage.batchVectorD0.values.all { it in 0..coverage.vectorD0 })
        val rendered = StringBuilder().also { SuiteCoverageComparison(coverage, coverage).render(it) }.toString()
        assertTrue(Regex("distinct RFC-271 interaction vectors \\(D0\\) +${coverage.vectorD0} +${coverage.vectorD0} +0").containsMatchIn(rendered), rendered)
        assertTrue("batch vector D0 min/median/max" in rendered, rendered)
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
    fun `obligations a config cannot generate are reported as not applicable`() {
        val objectFragment = ResolverTestSuiteDefinition.OBJECT_FRAGMENT.config
        val variables = Config.default + (ResolverVariablesEnabled to true) + (ResolverFromArgumentVariablesEnabled to true)

        assertEquals(
            setOf("AVB", "AM", "A", "FO", "FI", "X", "LOE", "DA", "LX", "DD", "NE", "CRV"),
            inapplicableObligationIds(objectFragment).mapTo(mutableSetOf()) {
                resolverCoverageMetricCodes.getValue(it)
            }
        )
        assertEquals(setOf("argument_binding_path_has_multiple_steps"), inapplicableObligationIds(variables))
        assertEquals(
            setOf("FL", "FE"),
            inapplicableObligationIds(ResolverTestSuiteDefinition.EMPTY_OBJECT_FRAGMENT.config).mapTo(mutableSetOf()) {
                resolverCoverageMetricCodes.getValue(it)
            } - inapplicableObligationIds(objectFragment).map { resolverCoverageMetricCodes.getValue(it) }
        )
        assertEquals(
            setOf("argument_binding_path_has_multiple_steps", "list_object_field_error", "provider_read_crosses_list_boundary", "object_fragment_resolver_field_in_list_element"),
            inapplicableObligationIds(variables + (ListsEnabled to false))
        )
        assertEquals(
            setOf("argument_backed_variable_bound", "argument_binding_path_has_multiple_steps", "both_sources_on_one_occurrence", "both_sources_within_one_execution"),
            inapplicableObligationIds(Config.default + (ResolverVariablesEnabled to true)),
        )
    }

    @Test
    fun `the comparison marks inapplicable obligations instead of counting them as not exercised`(): Unit =
        runTest {
            val inapplicable = inapplicableObligationIds(definition.config)
            val annotated = CandidateCoverage.of("b", 0, measurement, inapplicableObligations = inapplicable)
            val comparison = SuiteCoverageComparison(coverage, annotated)

            assertTrue(inapplicable.isNotEmpty())
            val fanOut = comparison.metrics.single { it.id == "shared_provider_fan_out" }
            assertTrue(fanOut.leftApplicable && !fanOut.rightApplicable && !fanOut.inapplicable)
            assertTrue(SuiteCoverageComparison(annotated, annotated).metrics.single { it.id == "shared_provider_fan_out" }.inapplicable)
            val rendered = StringBuilder().also { comparison.render(it) }.toString()
            assertTrue(Regex("shared_provider_fan_out +0/0 +n/a").containsMatchIn(rendered), rendered)
            val page = SuiteCoverageComparisonPage.render(comparison, stored.batches(), stored.batches())
            val json = page.substringAfter("<script type=\"application/json\" id=\"data\">").substringBefore("</script>")
            val data = PropertyTestJson.read<Map<String, Any?>>(json.byteInputStream())
            val embedded = data["inapplicable"] as Map<*, *>
            assertEquals(emptyList<String>(), embedded["a"])
            assertEquals(inapplicable.toList(), embedded["b"])
            val vectorD0 = data["vectorD0"] as Map<*, *>
            assertEquals(coverage.vectorD0, vectorD0["a"])
            val resolverDiversity = (data["resolverDiversity"] as Map<*, *>)["a"] as Map<*, *>
            assertEquals(coverage.report.analysis.batchDiversity.size, resolverDiversity["batchCount"])
            assertTrue("diagnostic" in page)
            assertThrows<IllegalArgumentException> { CandidateCoverage.of("c", 0, measurement, inapplicableObligations = setOf("no_such_metric")) }
        }

    @Test
    fun `a metric only one config can generate is incomparable and does not decide the suite verdict`() {
        val fanOut = coverage.report.metrics.indexOfFirst { it.id == "shared_provider_fan_out" }
        val applicableWithSupport = coverage.bumped(fanOut)
        val inapplicable = CandidateCoverage.of("b", 0, measurement, inapplicableObligations = inapplicableObligationIds(definition.config))
        val comparison = SuiteCoverageComparison(applicableWithSupport, inapplicable)

        val metric = comparison.metrics.single { it.id == "shared_provider_fan_out" }
        assertTrue(metric.left.tests > metric.right.tests)
        assertTrue(metric.leftApplicable && !metric.rightApplicable)
        assertFalse(metric.comparable)
        assertEquals(CoverageVerdict.INCOMPARABLE, metric.verdict)
        assertEquals(CoverageVerdict.EQUAL, comparison.verdict)
        assertEquals(CoverageVerdict.EQUAL, SuiteCoverageComparison(inapplicable, applicableWithSupport).verdict)
        val rendered = StringBuilder().also { comparison.render(it) }.toString()
        assertTrue(Regex("shared_provider_fan_out +${metric.left.tests}/${metric.left.batches} +n/a +n/a").containsMatchIn(rendered), rendered)
        assertTrue("verdict (raw coverage): =" in rendered, rendered)
        assertEquals(CoverageVerdict.RIGHT_DOMINATES, SuiteCoverageComparison(coverage, applicableWithSupport).verdict)
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
    fun `the comparison page lists each resolver attribute either candidate reports once`(): Unit =
        runTest {
            val candidate = CoverageCandidate.Generated(definition, seed = 2, counts = TestCaseCount(2, 2))
            val comparison = ResolverTestSuiteComparer.compare(stored, candidate)

            val page = SuiteCoverageComparisonPage.render(comparison, stored.batches(), candidate.batches())

            val listed = page.embeddedList("attributes").map { it["id"] }
            assertEquals(listed.distinct(), listed)
            assertEquals((comparison.left.report.attributes + comparison.right.report.attributes).mapTo(mutableSetOf()) { it.id }, listed.toSet())
            val onCases = page.embeddedBatches().flatMap { batch -> (batch["cases"] as List<*>).flatMap { case -> (case as Map<*, *>)["attributes"] as List<*> } }
            assertTrue(onCases.isNotEmpty())
            assertTrue(listed.containsAll(onCases), "Case attributes missing from the page's attribute list: ${onCases.toSet() - listed.toSet()}")
        }

    @Test
    fun `the comparison page labels each obligation with its RFC-271 code and evidence type`(): Unit =
        runTest {
            val page = SuiteCoverageComparisonPage.render(SuiteCoverageComparison(coverage, coverage), stored.batches(), stored.batches())

            val behaviors = page.embeddedList("behaviors")
            assertEquals(coverage.report.metrics.map { it.id }, behaviors.map { it["id"] })
            assertEquals(listOf("A", "FO", "FI", "X", "B"), behaviors.filter { it["core"] == true }.map { it["code"] })
            assertEquals(behaviors.size, behaviors.map { it["code"] }.distinct().size)
            assertEquals(coverage.report.metrics.map { it.evidenceKind.name }, behaviors.map { it["evidence"] })
        }

    @Test
    fun `candidate specs apply generator config overrides by wire name`() {
        val candidate = assertInstanceOf(
            CoverageCandidate.Generated::class.java,
            CoverageCandidate.parse("${definition.id}@size=2:2,config=resolverVariablesEnabled:true;resolverVariableCount:2..4;errorValueWeight:0.25"),
        )

        assertEquals(true, candidate.config[ResolverVariablesEnabled])
        assertEquals(2..4, candidate.config[ResolverVariableCount])
        assertEquals(0.25, candidate.config[ErrorValueWeight])
        assertEquals(definition.config[FieldArgumentWeight], candidate.config[FieldArgumentWeight])
        assertTrue(candidate.label.endsWith(",config=resolverVariablesEnabled:true;resolverVariableCount:2..4;errorValueWeight:0.25"), candidate.label)
        assertThrows<IllegalArgumentException> { CoverageCandidate.parse("${definition.id}@config=noSuchKey:true") }
        assertThrows<IllegalArgumentException> { CoverageCandidate.parse("${definition.id}@config=resolverVariablesEnabled:yes") }
        assertThrows<IllegalArgumentException> { CoverageCandidate.parse("${definition.id}@config=errorValueWeight:2.0") }
    }

    @Test
    fun `a config sweep reports each variant's support by obligation code`(): Unit =
        runTest {
            val variants = ConfigSweepVariant.parseAll("base=|errors=errorValueWeight:0.5")

            val results = sweepConfigs(definition, TestCaseCount(2, 2), variants)

            assertEquals(listOf("base", "errors"), results.map { it.variant.name })
            assertTrue(results.all { it.cases in 1..4 && it.vectorD0 >= 1 })
            assertEquals(coverage.report.metrics.map { it.id }.toSet(), results.first().obligationTests.keys)
            val rendered = StringBuilder().also { results.render(it) }.toString()
            assertTrue(Regex("variant +cases +D0 +A +FO +FI +X +B ").containsMatchIn(rendered), rendered)
            assertTrue("errors (" in rendered && "errorValueWeight:0.5" in rendered, rendered)
            assertThrows<IllegalArgumentException> { ConfigSweepVariant.parseAll("missing-equals") }
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

private fun String.embeddedBatches(): List<Map<*, *>> = embeddedList("batches")

private fun String.embeddedList(key: String): List<Map<*, *>> {
    val json = substringAfter("<script type=\"application/json\" id=\"data\">").substringBefore("</script>")
    return (PropertyTestJson.read<Map<String, Any?>>(json.byteInputStream())[key] as List<*>).map { it as Map<*, *> }
}
