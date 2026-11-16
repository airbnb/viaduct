@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.coverage.CoverageCandidate
import viaduct.engine.runtime2.coverage.ResolverCoverageBatchSelection
import viaduct.engine.runtime2.coverage.ResolverCoverageReport
import viaduct.engine.runtime2.coverage.ResolverCoverageRequirement
import viaduct.engine.runtime2.coverage.ResolverCoverageRequirementKind
import viaduct.engine.runtime2.coverage.ResolverCoverageScoringCriteria
import viaduct.engine.runtime2.coverage.ResolverCoverageSignature
import viaduct.engine.runtime2.coverage.compareCandidate
import viaduct.engine.runtime2.coverage.inapplicableObligationIds
import viaduct.engine.runtime2.coverage.measureCoverage
import viaduct.engine.runtime2.coverage.resolverCoverageMetricCodes
import viaduct.engine.runtime2.coverage.retainingBatches
import viaduct.engine.runtime2.coverage.selectCoverageBatches
import viaduct.engine.runtime2.coverage.supports
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.propertytest.ResolverTestSuite
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

@Tag("IntegrationTest")
@Tag("Rfc271CoverageExperiment")
class StoredSuiteSelectionExperimentTest : CorrectResolutionPostTestPolicy, ResolutionDispatcherResource {
    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest
    ): ObjectEngineResult = operation.resolveWithTestDispatcher(selections)

    @ParameterizedTest
    @MethodSource("selectedDefinitions")
    fun `selecting batches from a candidate pool covers every pool combination and keeps every requirement the stored suite meets`(definition: ResolverTestSuiteDefinition): Unit =
        runBlocking {
            val storedBatchIds = ResolverTestSuite.load(definition).batches.map { "${definition.id}/${it.id}" }
            val poolSchemas = Integer.getInteger(POOL_SCHEMAS_PROPERTY, definition.counts.schemas)
            val measurement = measureCoverage(CoverageCandidate.Generated(definition, counts = definition.counts.copy(schemas = poolSchemas)))
            val poolReport = measurement.report()
            val storedReport = measurement.retainingBatches(storedBatchIds).report()
            val minimum = Integer.getInteger(MINIMUM_CASES_PROPERTY, definition.selectionMinimumCases)
            // Obligations the config cannot generate are not requirements, so an infeasible requirement means the pool is too small.
            val inapplicable = inapplicableObligationIds(definition.config)
            val requiredObligations = poolReport.metrics.map { it.id }.filterNot { it in inapplicable }
            val requiredAttributes = poolReport.attributes.map { it.id }.filter { id -> definition.selectionAttributePrefixes.any(id::startsWith) }
            val criteria =
                ResolverCoverageScoringCriteria(
                    requiredObligations.mapTo(linkedSetOf()) { ResolverCoverageRequirement(ResolverCoverageRequirementKind.OBLIGATION, it, minimum) } +
                        requiredAttributes.map { ResolverCoverageRequirement(ResolverCoverageRequirementKind.ATTRIBUTE, it, minimum) },
                )
            val coverEveryCombination = System.getProperty(COVER_EVERY_COMBINATION_PROPERTY, "true").toBoolean()
            val minimumCombinationCases = Integer.getInteger(MINIMUM_COMBINATION_CASES_PROPERTY, definition.selectionMinimumCombinationCases)
            val selection =
                selectCoverageBatches(poolReport.analysis.signatures, criteria, coverEveryCombination, definition.selectionMinimumBatches, minimumCombinationCases)
            val selectedReport = measurement.retainingBatches(selection.selectedBatchIds).report()
            val comparison = storedReport.compareCandidate(selectedReport, criteria)
            printSummary(definition, inapplicable, poolReport, storedReport, selectedReport, selection)

            assertEquals(selection.infeasibleRequirements, selection.unmetRequirements)
            if (coverEveryCombination) assertEquals(emptySet<Set<String>>(), selection.underSupportedCombinations)
            assertEquals(emptySet<ResolverCoverageRequirement>(), selection.underspreadRequirements)
            assertTrue(selection.poolCombinations.containsAll(storedReport.combinations()))
            val storedMet = criteria.requirements.filter { comparison.baselineScore.supportFor(it) >= it.minimumDistinctCases }
            assertTrue(storedMet.none { it in selection.unmetRequirements }, "Selection lost a requirement the stored suite meets")
            assertEquals(emptyList<String>(), selection.redundantBatches(poolReport.analysis.signatures, coverEveryCombination), "Every retained schema must be needed")
            val definitionSettings = minimum == definition.selectionMinimumCases && minimumCombinationCases == definition.selectionMinimumCombinationCases
            if (definition.retainedBatches.isNotEmpty() && poolSchemas == definition.counts.schemas && definitionSettings && coverEveryCombination) {
                assertEquals(selection.poolCombinations, storedReport.combinations(), "The stored suite must cover every combination in its pool")
                assertEquals(selection.selectedBatchIds, storedBatchIds, "Update ${definition.name} retainedBatches to the current selection")
            }
        }

    private fun ResolverCoverageBatchSelection.redundantBatches(
        signatures: List<ResolverCoverageSignature>,
        coverEveryCombination: Boolean,
    ): List<String> {
        val selected = selectedBatchIds.toSet()
        val kept = signatures.filter { it.test.batchId in selected }

        fun List<ResolverCoverageSignature>.meetsSelection(): Boolean =
            support.all { (requirement, selected) -> count { it.supports(requirement) } >= minOf(selected, requirement.minimumDistinctCases) } &&
                batchSupport.all { (requirement, selected) ->
                    filter { it.supports(requirement) }.map { it.test.batchId }.distinct().size >= minOf(selected, minimumBatches)
                } &&
                (!coverEveryCombination || combinationSupport.all { (combination, selected) -> count { it.obligations == combination } >= minOf(selected, minimumCombinationCases) })
        return selectedBatchIds.filter { batchId -> kept.filter { it.test.batchId != batchId }.meetsSelection() }
    }

    // Pool-versus-selected support per requirement and combination, so a run shows what the retained batches keep and what the config cannot produce.
    private fun printSummary(
        definition: ResolverTestSuiteDefinition,
        inapplicable: Set<String>,
        pool: ResolverCoverageReport,
        stored: ResolverCoverageReport,
        selected: ResolverCoverageReport,
        selection: ResolverCoverageBatchSelection,
    ) {
        println(
            "selection suite=${definition.id} pool=${pool.executions.size} cases stored=${stored.executions.size} cases selected=${selected.executions.size} cases in ${selection.selectedBatchIds.size} batches"
        )
        println("  not applicable by config: ${inapplicable.ifEmpty { setOf("none") }.joinToString()}")
        listOf("pool" to pool, "stored" to stored, "selected" to selected).forEach { (name, report) ->
            val diversity = report.analysis.suite.diversity
            println("  %-8s D0=%d D1=%.2f D2=%.2f dominant=%.3f".format(name, diversity.d0, diversity.d1, diversity.d2, diversity.dominantSignatureShare))
        }
        println("  requirement (tests pool/selected, batches pool/selected):")
        selection.criteria.requirements.sortedWith(compareBy({ it.kind }, { it.id })).forEach { requirement ->
            val marker = if (requirement in selection.unmetRequirements) " unmet" else ""
            println(
                "    %-60s %5d/%-5d %4d/%-4d%s".format(
                    requirement.id,
                    selection.poolSupport.getValue(requirement),
                    selection.support.getValue(requirement),
                    selection.poolBatchSupport.getValue(requirement),
                    selection.batchSupport.getValue(requirement),
                    marker,
                ),
            )
        }
        println("  combination (cases pool/selected, minimum ${selection.minimumCombinationCases}):")
        selection.poolCombinations.sortedWith(compareBy({ it.size }, { it.joinToString() })).forEach { combination ->
            val label = combination.sorted().joinToString("+", transform = resolverCoverageMetricCodes::getValue).ifEmpty { "(none)" }
            println("    %-24s %5d/%-5d".format(label, selection.poolCombinationSupport.getValue(combination), selection.combinationSupport.getValue(combination)))
        }
    }

    private fun ResolverCoverageReport.combinations(): Set<Set<String>> = analysis.suite.diversity.frequencies.mapTo(mutableSetOf()) { it.obligations }

    companion object {
        /** Suites with retained batches, or the one suite named by [SUITE_PROPERTY] to try selection on a suite not yet stored that way. */
        @JvmStatic
        fun selectedDefinitions(): List<ResolverTestSuiteDefinition> =
            System.getProperty(SUITE_PROPERTY)?.let { listOf(ResolverTestSuiteDefinition.byId(it)) }
                ?: ResolverTestSuiteDefinition.entries.filter { it.retainedBatches.isNotEmpty() }

        const val SUITE_PROPERTY = "viaduct.runtime2.selection.suite"

        const val POOL_SCHEMAS_PROPERTY = "viaduct.runtime2.selection.poolSchemas"
        const val MINIMUM_CASES_PROPERTY = "viaduct.runtime2.selection.minimumCases"
        const val MINIMUM_COMBINATION_CASES_PROPERTY = "viaduct.runtime2.selection.minimumCombinationCases"
        const val COVER_EVERY_COMBINATION_PROPERTY = "viaduct.runtime2.selection.coverEveryCombination"
    }
}
