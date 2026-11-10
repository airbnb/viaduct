package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.coverage.RFC_271_CORE_OBLIGATION_IDS
import viaduct.engine.runtime2.coverage.ResolutionRole
import viaduct.engine.runtime2.coverage.ResolverCoverageCsv
import viaduct.engine.runtime2.coverage.ResolverCoverageReportJson
import viaduct.engine.runtime2.coverage.measureSavedSuiteCoverage
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.propertytest.ResolverTestCaseId
import viaduct.engine.runtime2.propertytest.ResolverTestSuite
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

private const val RFC_271_COVERAGE_EXPERIMENT_ENV = "VIADUCT_RFC271_COVERAGE_EXPERIMENT"

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
            val report = measurement.report()
            val reportJson = StringBuilder().also { ResolverCoverageReportJson.write(report, it) }.toString()
            val restoredReport = ResolverCoverageReportJson.read(reportJson.reader())

            assertEquals(measurement.rows, restoredRows)
            assertEquals(report, restoredReport)

            val metricSupport = report.metrics.associate { it.id to it.support.tests }
            val coreSupport = metricSupport.filterKeys { it in RFC_271_CORE_OBLIGATION_IDS }
            val supplementalSupport = metricSupport.filterKeys { it !in RFC_271_CORE_OBLIGATION_IDS }
            assertEquals(RFC_271_CORE_OBLIGATION_IDS, coreSupport.keys)
            val diversity = report.analysis.suite.diversity
            val observedAttributes = report.attributes.filter { it.support.tests > 0 }.map { it.id }
            println(
                "RFC-271 suite=${definition.id} " +
                    "cases=${measurement.executions.size}/${suite.caseCount} " +
                    "rows=${measurement.rows.size} dependencies=${measurement.dependencies.size} " +
                    "coreSupport=$coreSupport supplementalSupport=$supplementalSupport " +
                    "observedAttributes=$observedAttributes " +
                    "D0=${diversity.d0} D1=${diversity.d1} D2=${diversity.d2} " +
                    "dominantShare=${diversity.dominantSignatureShare} " +
                    "similarProfilePairs=${report.analysis.highlySimilarProfiles.size}"
            )
        }

    @ParameterizedTest
    @EnumSource(ResolverTestSuiteDefinition::class)
    @Tag("Rfc271CoverageExperiment")
    @EnabledIfEnvironmentVariable(named = RFC_271_COVERAGE_EXPERIMENT_ENV, matches = "true")
    fun `experimental coverage sample measures one case per saved suite`(definition: ResolverTestSuiteDefinition): Unit =
        runTest {
            val suite = ResolverTestSuite.load(definition)
            val sampleCase = requireNotNull(suite.testCaseIds().firstOrNull()) {
                "Saved suite ${definition.id} contains no test cases"
            }
            val measurement = measureSavedSuiteCoverage(suite, selectedCase = sampleCase)
            assertEquals(1, measurement.executions.size)
            val csv = StringBuilder().also { ResolverCoverageCsv.write(measurement.rows, it) }.toString()
            val restoredRows = ResolverCoverageCsv.read(csv.reader())
            val report = measurement.report()
            val reportJson = StringBuilder().also { ResolverCoverageReportJson.write(report, it) }.toString()
            val restoredReport = ResolverCoverageReportJson.read(reportJson.reader())

            assertEquals(measurement.rows, restoredRows)
            assertEquals(report, restoredReport)

            val metricSupport = report.metrics.associate { it.id to it.support.tests }
            val coreSupport = metricSupport.filterKeys { it in RFC_271_CORE_OBLIGATION_IDS }
            val supplementalSupport = metricSupport.filterKeys { it !in RFC_271_CORE_OBLIGATION_IDS }
            assertEquals(RFC_271_CORE_OBLIGATION_IDS, coreSupport.keys)
            val diversity = report.analysis.suite.diversity
            val observedAttributes = report.attributes.filter { it.support.tests > 0 }.map { it.id }
            println(
                "RFC-271 experimental suite=${definition.id} " +
                    "sampledCases=${measurement.executions.size}/${suite.caseCount} sampleCase=$sampleCase " +
                    "rows=${measurement.rows.size} dependencies=${measurement.dependencies.size} " +
                    "coreSupport=$coreSupport supplementalSupport=$supplementalSupport " +
                    "observedAttributes=$observedAttributes " +
                    "D0=${diversity.d0} D1=${diversity.d1} D2=${diversity.d2} " +
                    "dominantShare=${diversity.dominantSignatureShare} " +
                    "similarProfilePairs=${report.analysis.highlySimilarProfiles.size}"
            )
        }

    @Test
    fun `coverage measurement keeps intermediate query fragment rows without treating them as standalone validated results`() =
        runTest {
            val suite = ResolverTestSuite.load(ResolverTestSuiteDefinition.QUERY_FRAGMENT)
            val failingCase = ResolverTestCaseId.parse("query-fragment/b001/b31e8e/96f651")

            val measurement = measureSavedSuiteCoverage(suite, selectedCase = failingCase)

            assertEquals(1, measurement.executions.size)
            assertTrue(measurement.rows.any { it.test.queryId == failingCase.query && it.resultPath.contains("queryFragment-") })
        }
}
