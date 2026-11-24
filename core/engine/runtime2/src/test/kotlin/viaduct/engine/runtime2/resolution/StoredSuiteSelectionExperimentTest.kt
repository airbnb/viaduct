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
import viaduct.engine.runtime2.coverage.ResolverCoverageMeasurement
import viaduct.engine.runtime2.coverage.ResolverCoverageReport
import viaduct.engine.runtime2.coverage.ResolverCoverageRequirement
import viaduct.engine.runtime2.coverage.ResolverCoverageRequirementKind
import viaduct.engine.runtime2.coverage.ResolverCoverageScoringCriteria
import viaduct.engine.runtime2.coverage.ResolverCoverageSignature
import viaduct.engine.runtime2.coverage.ResolverInteractionVector
import viaduct.engine.runtime2.coverage.compareCandidate
import viaduct.engine.runtime2.coverage.inapplicableObligationIds
import viaduct.engine.runtime2.coverage.interactionVectorObservations
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
            val storedMeasurement = measurement.retainingBatches(storedBatchIds)
            val storedReport = storedMeasurement.report()
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
            val selectedMeasurement = measurement.retainingBatches(selection.selectedBatchIds)
            val selectedReport = selectedMeasurement.report()
            val comparison = storedReport.compareCandidate(selectedReport, criteria)
            printSummary(
                definition,
                inapplicable,
                poolReport,
                storedReport,
                selectedReport,
                selection,
                coverEveryCombination,
                measurement,
                storedMeasurement,
                selectedMeasurement,
            )

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
        coverEveryCombination: Boolean,
        poolMeasurement: ResolverCoverageMeasurement,
        storedMeasurement: ResolverCoverageMeasurement,
        selectedMeasurement: ResolverCoverageMeasurement,
    ) {
        println(
            "selection suite=${definition.id} pool=${pool.executions.size} cases stored=${stored.executions.size} cases selected=${selected.executions.size} cases in ${selection.selectedBatchIds.size} batches"
        )
        println("  selected batches: ${selection.selectedBatchIds.joinToString()}")
        println("  not applicable by config: ${inapplicable.ifEmpty { setOf("none") }.joinToString()}")
        println("  selected batches: ${selection.selectedBatchIds.joinToString { it.substringAfterLast('/') }}")
        listOf(
            Triple("pool", pool, poolMeasurement.vectorD0()),
            Triple("stored", stored, storedMeasurement.vectorD0()),
            Triple("selected", selected, selectedMeasurement.vectorD0()),
        ).forEach { (name, report, d0) ->
            val diversity = report.analysis.batchDiversity
            val diagnostic = if (diversity.isEmpty()) {
                "-"
            } else {
                "D0 %.2f D1 %.2f D2 %.2f".format(
                    diversity.sumOf { it.d0.toDouble() } / diversity.size,
                    diversity.sumOf { it.d1 } / diversity.size,
                    diversity.sumOf { it.d2 } / diversity.size,
                )
            }
            println("  %-8s vector D0=%d; resolver-fingerprint diversity (diagnostic only, mean batch D0/D1/D2)=%s".format(name, d0, diagnostic))
        }
        printBatchRemovalSummary(poolMeasurement)
        printIterativeRemovalSummary(poolMeasurement, selection, coverEveryCombination)
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

    /** Reports each batch's distinct vectors and the vector support lost by removing that batch. */
    private fun printBatchRemovalSummary(measurement: ResolverCoverageMeasurement) {
        val vectorsByBatch = measurement.interactionVectorObservations()
            .groupBy { it.test.batchId }
            .mapValues { (_, observations) -> observations.mapTo(mutableSetOf()) { it.vector } }
        val supportByVector = vectorsByBatch.values.flatten().groupingBy { it }.eachCount()

        data class BatchContribution(
            val batchId: String,
            val cases: Int,
            val vectors: Int,
            val uniqueVectors: Int,
            val supportTwoLoss: Int,
            val supportThreeLoss: Int,
        )
        val contributions = vectorsByBatch.map { (batchId, vectors) ->
            BatchContribution(
                batchId = batchId,
                cases = measurement.executions.count { it.test.batchId == batchId },
                vectors = vectors.size,
                uniqueVectors = vectors.count { supportByVector.getValue(it) == 1 },
                supportTwoLoss = vectors.count { supportByVector.getValue(it) == 2 },
                supportThreeLoss = vectors.count { supportByVector.getValue(it) == 3 },
            )
        }.sortedWith(compareByDescending<BatchContribution> { it.uniqueVectors }.thenByDescending { it.vectors }.thenBy { it.batchId })
        println("  batch removal (vectors unique to batch = D0 loss on removal; support2/3 = vectors that would fall below that support floor):")
        println("    batch      cases  batchD0  unique (D0 loss)  support2-loss  support3-loss  D0-after-removal")
        contributions.forEach { contribution ->
            println(
                "    %-10s %5d %8d %15d %13d %13d %16d".format(
                    contribution.batchId.substringAfterLast('/'),
                    contribution.cases,
                    contribution.vectors,
                    contribution.uniqueVectors,
                    contribution.supportTwoLoss,
                    contribution.supportThreeLoss,
                    supportByVector.size - contribution.uniqueVectors,
                ),
            )
        }
    }

    /** Recomputes marginal vector loss after each removal while preserving the selection's coverage goals. */
    private fun printIterativeRemovalSummary(
        measurement: ResolverCoverageMeasurement,
        selection: ResolverCoverageBatchSelection,
        coverEveryCombination: Boolean,
    ) {
        val vectorsByBatch = measurement.interactionVectorObservations()
            .groupBy { it.test.batchId }
            .mapValues { (_, observations) -> observations.mapTo(mutableSetOf()) { it.vector } }
        val signatures = measurement.report().analysis.signatures
        val poolCombinationSupport = signatures.groupingBy { it.obligations }.eachCount()
        val caseCountByBatch = measurement.executions.groupingBy { it.test.batchId }.eachCount()
        val remaining = caseCountByBatch.keys.toMutableSet()

        fun vectorsIn(batchIds: Set<String>): Set<ResolverInteractionVector> = batchIds.flatMapTo(mutableSetOf()) { vectorsByBatch[it].orEmpty() }

        fun canRemove(batchId: String): Boolean {
            val remainingAfterRemoval = remaining - batchId
            val keptSignatures = signatures.filter { it.test.batchId in remainingAfterRemoval }
            val requirementsMet = selection.criteria.requirements.all { requirement ->
                val supporting = keptSignatures.filter { it.supports(requirement) }
                val requiredCases = minOf(requirement.minimumDistinctCases, selection.poolSupport.getValue(requirement))
                val requiredBatches = minOf(selection.minimumBatches, selection.poolBatchSupport.getValue(requirement))
                supporting.size >= requiredCases && supporting.map { it.test.batchId }.distinct().size >= requiredBatches
            }
            val combinationsMet = !coverEveryCombination || poolCombinationSupport.all { (combination, support) ->
                keptSignatures.count { it.obligations == combination } >= minOf(selection.minimumCombinationCases, support)
            }
            return requirementsMet && combinationsMet
        }

        println("  iterative removal (each step recomputes D0; preserves obligation, batch-spread, and combination goals):")
        var step = 0
        while (true) {
            val currentVectors = vectorsIn(remaining)
            val removable = remaining.filter(::canRemove).map { batchId ->
                val after = remaining - batchId
                val afterVectors = vectorsIn(after)
                Triple(batchId, currentVectors.size - afterVectors.size, caseCountByBatch[batchId] ?: 0)
            }.sortedWith(compareBy<Triple<String, Int, Int>> { it.second }.thenBy { it.third }.thenBy { it.first })
            val (batchId, vectorLoss, cases) = removable.firstOrNull() ?: break
            remaining -= batchId
            step++
            println(
                "    remove %2d: %-10s cases=%d marginalVectorLoss=%d remainingBatches=%d remainingCases=%d vectorD0=%d".format(
                    step,
                    batchId.substringAfterLast('/'),
                    cases,
                    vectorLoss,
                    remaining.size,
                    remaining.sumOf { caseCountByBatch[it] ?: 0 },
                    vectorsIn(remaining).size,
                ),
            )
        }
        println(
            "    cutoff: batches=%s cases=%d vectorD0=%d; no remaining batch can be removed without breaking a configured goal".format(
                remaining.sorted().joinToString { it.substringAfterLast('/') },
                remaining.sumOf { caseCountByBatch[it] ?: 0 },
                vectorsIn(remaining).size,
            ),
        )
    }

    private fun ResolverCoverageReport.combinations(): Set<Set<String>> = analysis.signatures.mapTo(mutableSetOf()) { it.obligations }

    private fun ResolverCoverageMeasurement.vectorD0(): Int = interactionVectorObservations().mapTo(mutableSetOf()) { it.vector }.size

    companion object {
        /** Suites with retained batches, or the one suite named by [SUITE_PROPERTY] to try selection on a suite not yet stored that way. */
        @JvmStatic
        fun selectedDefinitions(): List<ResolverTestSuiteDefinition> {
            val requested = System.getProperty(SUITE_PROPERTY) ?: return ResolverTestSuiteDefinition.entries.filter { it.retainedBatches.isNotEmpty() }
            val ids = requested.split(',').map(String::trim)
            require(ids.all { it.isNotEmpty() }) { "$SUITE_PROPERTY must contain nonempty suite ids" }
            require(ids.distinct().size == ids.size) { "$SUITE_PROPERTY must not contain duplicate suite ids" }
            return ids.map { ResolverTestSuiteDefinition.byId(it) }
        }

        const val SUITE_PROPERTY = "viaduct.runtime2.selection.suite"

        const val POOL_SCHEMAS_PROPERTY = "viaduct.runtime2.selection.poolSchemas"
        const val MINIMUM_CASES_PROPERTY = "viaduct.runtime2.selection.minimumCases"
        const val MINIMUM_COMBINATION_CASES_PROPERTY = "viaduct.runtime2.selection.minimumCombinationCases"
        const val COVER_EVERY_COMBINATION_PROPERTY = "viaduct.runtime2.selection.coverEveryCombination"
    }
}
