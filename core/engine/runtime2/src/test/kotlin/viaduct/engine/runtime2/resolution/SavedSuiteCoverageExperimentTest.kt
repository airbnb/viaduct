package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.coverage.ResolutionRole
import viaduct.engine.runtime2.coverage.ResolverCoverageCsv
import viaduct.engine.runtime2.coverage.measureSavedSuiteCoverage
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.propertytest.ResolverTestSuite
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class SavedSuiteCoverageExperimentTest : CorrectResolutionPostTestPolicy, ResolutionDispatcherResource {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)

    @ParameterizedTest
    @EnumSource(
        value = ResolverTestSuiteDefinition::class,
        names = ["ROOT_FIELD_REFERENCE", "SOMETIMES_PASSIVE", "RESOLUTION_MIXED_VARIABLES"],
    )
    fun `coverage measurements round trip for representative saved suites`(definition: ResolverTestSuiteDefinition): Unit =
        runTest {
            val suite = ResolverTestSuite.load(definition)
            val measurement = measureSavedSuiteCoverage(suite)
            assertEquals(suite.caseCount, measurement.executions.size)
            assertTrue(measurement.rows.isNotEmpty(), "${definition.id} produced no resolver coverage rows")
            assertTrue(
                measurement.rows.any { it.resolutionRole == ResolutionRole.INVOKED },
                "${definition.id} produced no invoked resolver occurrences",
            )
            val csv = StringBuilder().also { ResolverCoverageCsv.write(measurement.rows, it) }.toString()
            val restoredRows = ResolverCoverageCsv.read(csv.reader())

            assertEquals(measurement.rows, restoredRows)

            val roleCounts =
                ResolutionRole.entries.associateWith { role ->
                    measurement.rows.count { it.resolutionRole == role }
                }
            println(
                "RFC-271 suite=${definition.id} " +
                    "executions=${measurement.executions.size} rows=${measurement.rows.size} " +
                    "roles=$roleCounts " +
                    "sameOccurrence=${measurement.bothSourcesOnOneOccurrence()} " +
                    "coexistence=${measurement.bothSourcesWithinOneExecution()}"
            )
        }
}
