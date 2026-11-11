package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import viaduct.engine.runtime2.coverage.ResolverCoverageRequirement
import viaduct.engine.runtime2.coverage.ResolverCoverageRequirementKind
import viaduct.engine.runtime2.coverage.ResolverCoverageRow
import viaduct.engine.runtime2.coverage.ResolverCoverageScoreRelation
import viaduct.engine.runtime2.coverage.ResolverCoverageScoringCriteria
import viaduct.engine.runtime2.coverage.compareSavedSuiteCandidate
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
    fun `measuring one batch replays all and only its cases`(): Unit =
        runTest {
            val batch = suite.batches.first()
            val expected = suite.testCaseIds().filter { it.batch == batch.id }.map(::coverageTestId)

            val batchMeasurement = measureSavedSuiteCoverage(suite, selectedBatchId = batch.id)

            assertEquals(expected, batchMeasurement.executions.map { it.test })
        }

    @Test
    fun `replaying a saved case reproduces its occurrence rows`(): Unit =
        runTest {
            val example = measurement.rows.first()
            val replayed = replayRecordsFor(example).occurrences
            val expected = measurement.rows.filter { it.test == example.test }
            assertEquals(expected.withoutObservationOrder(), replayed.withoutObservationOrder())
        }

    // Resolution may observe concurrent occurrences in a different order, and occurrence IDs number them in that order.
    private fun List<ResolverCoverageRow>.withoutObservationOrder(): List<ResolverCoverageRow> = map { it.copy(occurrenceId = "occurrence") }.sortedBy(ResolverCoverageRow::toString)

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

    @Test
    fun `candidate scoring rejects a saved suite missing a required behavior`() =
        runTest {
            val required =
                ResolverCoverageRequirement(
                    ResolverCoverageRequirementKind.OBLIGATION,
                    "both_sources_within_one_execution",
                    1,
                )
            val criteria =
                ResolverCoverageScoringCriteria(setOf(required))
            val emptySuite = ResolverTestSuite.load(ResolverTestSuiteDefinition.EMPTY_OBJECT_FRAGMENT)
            val comparison = compareSavedSuiteCandidate(suite, emptySuite, criteria)

            assertFalse(comparison.candidateScore.meetsRequirements)
            assertEquals(setOf(required), comparison.scoreChange.unmetRequirements)
            assertEquals(ResolverCoverageScoreRelation.INFEASIBLE, comparison.scoreChange.relation)
        }

    private fun coverageTestId(testCaseId: ResolverTestCaseId): CoverageTestId =
        CoverageTestId.from(
            testCaseId,
            suite.batches.single { it.id == testCaseId.batch }.ids(),
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
