package viaduct.engine.runtime2.coverage

import java.util.UUID
import viaduct.engine.runtime2.contract.GeneratedCaseAssertions
import viaduct.engine.runtime2.contract.ResolverContract
import viaduct.engine.runtime2.contract.assertAll
import viaduct.engine.runtime2.contract.observeGeneratedCase
import viaduct.engine.runtime2.propertytest.ResolverTestCaseId
import viaduct.engine.runtime2.propertytest.ResolverTestSuite

/** Uses persisted inputs, never generator seeds. Failure or interruption yields no measurement. */
suspend fun ResolverContract.measureSavedSuiteCoverage(
    suite: ResolverTestSuite,
    runId: String = UUID.randomUUID().toString(),
    selectedCase: ResolverTestCaseId? = null,
    selectedBatchId: String? = null,
): ResolverCoverageMeasurement = measureCoverage(CoverageCandidate.Stored(suite, selectedCase, selectedBatchId), runId)

/** Failure or interruption yields no measurement. */
suspend fun ResolverContract.measureCoverage(
    candidate: CoverageCandidate,
    runId: String = UUID.randomUUID().toString(),
): ResolverCoverageMeasurement {
    require(runId.isNotBlank())
    val executions = mutableListOf<CoverageExecution>()
    val rows = mutableListOf<ResolverCoverageRow>()
    val dependencies = mutableListOf<ResolverDependencyRow>()
    val expectedCases = candidate.replay { world, testCase, test ->
        val observation = observeGeneratedCase(world, testCase).assertAll(
            GeneratedCaseAssertions.defaultGeneratedContract + GeneratedCaseAssertions.fromFieldBindings,
        )
        val execution = CoverageExecution(runId, "execution-${executions.size + 1}", test)
        executions += execution
        // The permutation execution validates correctness; it is not another supporting test.
        val records = observation.ordinary.subject.coverageRecords(execution)
        rows += records.occurrences
        dependencies += records.dependencies
    }
    check(executions.size == expectedCases) { "Incomplete ${candidate.label} measurement: ${executions.size} of $expectedCases cases" }
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
