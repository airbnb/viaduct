package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.contract.GeneratedCaseAssertions
import viaduct.engine.runtime2.contract.assertAll
import viaduct.engine.runtime2.contract.observeGeneratedCase
import viaduct.engine.runtime2.coverage.CoverageTestId
import viaduct.engine.runtime2.coverage.ResolverCoverageMeasurement
import viaduct.engine.runtime2.coverage.ResolverCoverageRecords
import viaduct.engine.runtime2.coverage.ResolverCoverageRow
import viaduct.engine.runtime2.coverage.coverageRecords
import viaduct.engine.runtime2.coverage.measureSavedSuiteCoverage
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.propertytest.ResolverTestCaseId
import viaduct.engine.runtime2.propertytest.ResolverTestSuite
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoredSuiteCoverageExampleTest : CorrectResolutionPostTestPolicy, ResolutionDispatcherResource {
    private lateinit var suite: ResolverTestSuite
    private lateinit var measurement: ResolverCoverageMeasurement
    private lateinit var sameCaseReruns: List<ResolverCoverageMeasurement>

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)

    @BeforeAll
    fun measureSavedCases(): Unit =
        runTest {
            suite = ResolverTestSuite.load(ResolverTestSuiteDefinition.CONSTRUCTION_WITNESS)
            measurement = measureSavedSuiteCoverage(suite)
            val firstCase = suite.testCaseIds().first()
            sameCaseReruns =
                listOf(
                    measureSavedSuiteCoverage(suite, selectedCase = firstCase),
                    measureSavedSuiteCoverage(suite, selectedCase = firstCase),
                )
        }

    @Test
    fun `coverage executions use the saved suite IDs`() {
        val expected = suite.testCaseIds().map(::coverageTestId)
        assertEquals(expected, measurement.executions.map { it.test })
    }

    @Test
    fun `replaying a saved case reproduces its occurrence rows`(): Unit =
        runTest {
            val example = measurement.rows.first()
            val replayed = replayRecordsFor(example).occurrences
            val expected = measurement.rows.filter { it.test == example.test }
            assertEquals(expected, replayed)
        }

    @Test
    fun `replaying a test preserves its saved suite ID`() {
        val testIds = sameCaseReruns.flatMap { it.executions }.map { it.test }.toSet()
        assertEquals(setOf(coverageTestId(suite.testCaseIds().first())), testIds)
    }

    @Test
    fun `separate suite runs get separate run IDs`() {
        val runIds = sameCaseReruns.flatMap { it.executions }.map { it.runId }.distinct()
        assertEquals(2, runIds.size)
    }

    private fun coverageTestId(testCaseId: ResolverTestCaseId): CoverageTestId =
        CoverageTestId.from(
            testCaseId,
            suite.batches.single { it.id == testCaseId.batch }.schema,
        )

    private suspend fun replayRecordsFor(example: ResolverCoverageRow): ResolverCoverageRecords {
        val caseId = suite.testCaseIds().single { coverageTestId(it) == example.test }
        var records: ResolverCoverageRecords? = null
        suite.replay(selected = caseId) { world, testCase ->
            val observation = observeGeneratedCase(world, testCase).assertAll(
                GeneratedCaseAssertions.defaultGeneratedContract +
                    GeneratedCaseAssertions.fromFieldBindings,
            )
            records = observation.ordinary.subject.coverageRecords(example.execution)
        }
        return checkNotNull(records) { "The selected saved case was not replayed" }
    }
}
