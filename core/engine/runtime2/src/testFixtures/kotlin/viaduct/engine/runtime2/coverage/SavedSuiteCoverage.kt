package viaduct.engine.runtime2.coverage

import java.util.UUID
import viaduct.engine.runtime2.arbitrary.ResolverTestCase
import viaduct.engine.runtime2.contract.GeneratedCaseAssertions
import viaduct.engine.runtime2.contract.ResolverContract
import viaduct.engine.runtime2.contract.assertAll
import viaduct.engine.runtime2.contract.observeGeneratedCase
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.propertytest.ResolverTestCaseId
import viaduct.engine.runtime2.propertytest.ResolverTestSuite

/** Uses persisted inputs, never generator seeds. Failure or interruption yields no measurement. */
suspend fun ResolverContract.measureSavedSuiteCoverage(
    suite: ResolverTestSuite,
    runId: String = UUID.randomUUID().toString(),
    selectedCase: ResolverTestCaseId? = null,
    selectedBatchId: String? = null,
): ResolverCoverageMeasurement {
    require(runId.isNotBlank())
    require(selectedCase == null || selectedBatchId == null) { "Select either one case or one batch, not both" }
    val executions = mutableListOf<CoverageExecution>()
    val rows = mutableListOf<ResolverCoverageRow>()
    val dependencies = mutableListOf<ResolverDependencyRow>()

    suspend fun recordCase(
        world: TestWorld,
        testCase: ResolverTestCase,
    ) {
        val observation = observeGeneratedCase(world, testCase).assertAll(
            GeneratedCaseAssertions.defaultGeneratedContract + GeneratedCaseAssertions.fromFieldBindings,
        )
        val testCaseId = ResolverTestCaseId.parse(requireNotNull(testCase.testCaseId) { "Saved test case has no stable ID" })
        val schemaId = suite.batches.single { it.id == testCaseId.batch }.schema
        val test = CoverageTestId.from(testCaseId, schemaId)
        val execution = CoverageExecution(runId, "execution-${executions.size + 1}", test)
        executions += execution
        // The permutation execution validates correctness; it is not another supporting test.
        val records = observation.ordinary.subject.coverageRecords(execution)
        rows += records.occurrences
        dependencies += records.dependencies
    }

    if (selectedBatchId == null) {
        val run = suite.replay(selected = selectedCase, property = ::recordCase)
        check(run.attemptedCases == run.expectedCases) { "Incomplete saved-suite measurement" }
    } else {
        val expectedCases = suite.testCaseIds().count { it.batch == selectedBatchId }
        require(expectedCases > 0) { "Resolver test suite ${suite.definition.id} holds no test cases in batch $selectedBatchId" }
        val run = suite.replay(selectedBatchId = selectedBatchId, property = ::recordCase)
        check(run.attemptedCases == expectedCases) {
            "Saved-suite batch $selectedBatchId replayed ${run.attemptedCases} cases; expected $expectedCases"
        }
    }

    val expectedCases =
        when {
            selectedBatchId != null -> suite.testCaseIds().count { it.batch == selectedBatchId }
            selectedCase != null -> 1
            else -> suite.caseCount
        }
    check(executions.size == expectedCases) { "Incomplete saved-suite measurement: ran ${executions.size} cases; expected $expectedCases" }
    return ResolverCoverageMeasurement(executions, rows, dependencies = dependencies)
}

/** Compares saved suites through the same correctness-checked replay used for coverage measurement. */
suspend fun ResolverContract.compareSavedSuiteCandidate(
    baseline: ResolverTestSuite,
    candidate: ResolverTestSuite,
    scoringCriteria: ResolverCoverageScoringCriteria,
): ResolverCoverageCandidateComparison {
    val baselineReport = measureSavedSuiteCoverage(baseline).report()
    val candidateReport = measureSavedSuiteCoverage(candidate).report()
    return baselineReport.compareCandidate(candidateReport, scoringCriteria)
}

/** Replays a retained set of suites and measures their combined coverage. */
suspend fun ResolverContract.measureSavedSuiteSetCoverage(suites: Collection<ResolverTestSuite>): ResolverCoverageMeasurement {
    require(suites.isNotEmpty()) { "At least one saved suite is required" }
    require(suites.map { it.definition.id }.distinct().size == suites.size) { "Saved suite IDs must be unique" }
    val measurements = mutableListOf<ResolverCoverageMeasurement>()
    for (suite in suites) measurements += measureSavedSuiteCoverage(suite)
    return combineCoverageMeasurements(measurements)
}

/** Compares adding, removing, or revising suites in the combined retained set. */
suspend fun ResolverContract.compareSavedSuiteSetCandidate(
    baseline: Collection<ResolverTestSuite>,
    candidate: Collection<ResolverTestSuite>,
    criteria: ResolverCoverageSuiteSetCriteria,
): ResolverCoverageSuiteSetComparison {
    require(baseline.isNotEmpty() && candidate.isNotEmpty()) { "Baseline and candidate must each contain a saved suite" }
    val baselineIds = baseline.map { it.definition.id }.toSet()
    val candidateIds = candidate.map { it.definition.id }.toSet()
    require(baselineIds.size == baseline.size && candidateIds.size == candidate.size) { "Saved suite IDs must be unique" }
    val baselineReport = measureSavedSuiteSetCoverage(baseline).report()
    val candidateReport = measureSavedSuiteSetCoverage(candidate).report()
    return baselineReport.compareSuiteSetCandidate(candidateReport, baselineIds, candidateIds, criteria)
}
