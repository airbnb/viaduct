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
): ResolverCoverageMeasurement {
    require(runId.isNotBlank())
    val executions = mutableListOf<CoverageExecution>()
    val rows = mutableListOf<ResolverCoverageRow>()
    val run = suite.replay(selected = selectedCase) { world, testCase ->
        val observation = observeGeneratedCase(world, testCase).assertAll(
            GeneratedCaseAssertions.defaultGeneratedContract + GeneratedCaseAssertions.fromFieldBindings,
        )
        val testCaseId = ResolverTestCaseId.parse(requireNotNull(testCase.testCaseId) { "Saved test case has no stable ID" })
        val schemaId = suite.batches.single { it.id == testCaseId.batch }.schema
        val test = CoverageTestId.from(testCaseId, schemaId)
        val execution = CoverageExecution(runId, "execution-${executions.size + 1}", test)
        executions += execution
        // The permutation execution validates correctness; it is not another supporting test.
        rows += observation.ordinary.subject.coverageRows(execution)
    }
    check(run.attemptedCases == run.expectedCases && executions.size == run.expectedCases) { "Incomplete saved-suite measurement" }
    return ResolverCoverageMeasurement(executions, rows)
}
