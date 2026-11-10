package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.coverage.measureSavedSuiteCoverage
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.propertytest.ResolverTestSuite
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

private const val BATCH_LINE_COVERAGE_ENABLED = "VIADUCT_RFC271_BATCH_LINE_COVERAGE"
private const val BATCH_LINE_COVERAGE_SUITE = "VIADUCT_RFC271_BATCH_LINE_COVERAGE_SUITE"
private const val BATCH_LINE_COVERAGE_BATCH = "VIADUCT_RFC271_BATCH_LINE_COVERAGE_BATCH"

class SavedSuiteBatchLineCoverageTest : CorrectResolutionPostTestPolicy, ResolutionDispatcherResource {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)

    @Test
    @Tag("Rfc271BatchLineCoverage")
    @EnabledIfEnvironmentVariable(named = BATCH_LINE_COVERAGE_ENABLED, matches = "true")
    fun `replays one saved batch for line coverage`(): Unit =
        runTest {
            val suiteId = requireNotNull(System.getenv(BATCH_LINE_COVERAGE_SUITE)) {
                "$BATCH_LINE_COVERAGE_SUITE must name a saved suite"
            }
            val batchId = requireNotNull(System.getenv(BATCH_LINE_COVERAGE_BATCH)) {
                "$BATCH_LINE_COVERAGE_BATCH must name a batch in $suiteId"
            }
            val definition = requireNotNull(ResolverTestSuiteDefinition.entries.singleOrNull { it.id == suiteId }) {
                "Unknown saved suite $suiteId"
            }
            val suite = ResolverTestSuite.load(definition)
            val expectedCaseCount = suite.testCaseIds().count { it.batch == batchId }
            require(expectedCaseCount > 0) { "Saved suite $suiteId has no cases in batch $batchId" }

            val measurement = measureSavedSuiteCoverage(suite, selectedBatchId = batchId)

            assertEquals(expectedCaseCount, measurement.executions.size)
            assertTrue(measurement.rows.isNotEmpty(), "Saved batch $suiteId/$batchId produced no resolver rows")
            println("RFC-271 batch line coverage suite=$suiteId batch=$batchId cases=$expectedCaseCount")
        }
}
