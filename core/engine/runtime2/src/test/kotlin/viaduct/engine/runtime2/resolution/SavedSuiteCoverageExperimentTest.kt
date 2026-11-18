package viaduct.engine.runtime2.resolution

import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.contract.CorrectResolutionPostTestPolicy
import viaduct.engine.runtime2.coverage.CoverageCandidate
import viaduct.engine.runtime2.coverage.RFC_271_CORE_OBLIGATION_IDS
import viaduct.engine.runtime2.coverage.ResolutionRole
import viaduct.engine.runtime2.coverage.ResolverCoverageCsv
import viaduct.engine.runtime2.coverage.ResolverCoverageReportJson
import viaduct.engine.runtime2.coverage.ResolverInteractionParticipant
import viaduct.engine.runtime2.coverage.ResolverInteractionRelationship
import viaduct.engine.runtime2.coverage.ResolverInteractionVector
import viaduct.engine.runtime2.coverage.ResolverInteractionVectorObservation
import viaduct.engine.runtime2.coverage.ResolverOccurrenceSignature
import viaduct.engine.runtime2.coverage.combineCoverageMeasurements
import viaduct.engine.runtime2.coverage.interactionVectorObservations
import viaduct.engine.runtime2.coverage.measureCoverage
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
            val batchDiversity = report.analysis.batchDiversity
            val observedAttributes = report.attributes.filter { it.support.tests > 0 }.map { it.id }
            println(
                "RFC-271 suite=${definition.id} " +
                    "cases=${measurement.executions.size}/${suite.caseCount} " +
                    "rows=${measurement.rows.size} dependencies=${measurement.dependencies.size} " +
                    "coreSupport=$coreSupport supplementalSupport=$supplementalSupport " +
                    "observedAttributes=$observedAttributes " +
                    "batchDiversity=${batchDiversity.joinToString { "${it.batchId.substringAfterLast('/')}[D0=${it.d0},D1=${it.d1},D2=${it.d2}]" }} " +
                    "similarResolverPairs=${report.analysis.highlySimilarResolvers.size}"
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
            val batchDiversity = report.analysis.batchDiversity
            val observedAttributes = report.attributes.filter { it.support.tests > 0 }.map { it.id }
            println(
                "RFC-271 experimental suite=${definition.id} " +
                    "sampledCases=${measurement.executions.size}/${suite.caseCount} sampleCase=$sampleCase " +
                    "rows=${measurement.rows.size} dependencies=${measurement.dependencies.size} " +
                    "coreSupport=$coreSupport supplementalSupport=$supplementalSupport " +
                    "observedAttributes=$observedAttributes " +
                    "batchDiversity=${batchDiversity.joinToString { "${it.batchId.substringAfterLast('/')}[D0=${it.d0},D1=${it.d1},D2=${it.d2}]" }} " +
                    "similarResolverPairs=${report.analysis.highlySimilarResolvers.size}"
            )
        }

    @Test
    @Tag("Rfc271CoverageExperiment")
    @EnabledIfEnvironmentVariable(named = RFC_271_COVERAGE_EXPERIMENT_ENV, matches = "true")
    fun `batch selection pilot chooses a collection from a shared candidate pool`(): Unit =
        runTest {
            val candidateSuites = listOf(
                ResolverTestSuiteDefinition.CONSTRUCTION_WITNESS,
                ResolverTestSuiteDefinition.EMPTY_OBJECT_FRAGMENT,
                ResolverTestSuiteDefinition.RESOLUTION_QUERY_FRAGMENT,
                ResolverTestSuiteDefinition.RESOLUTION_OBJECT_FRAGMENT_FROM_OBJECT_FIELD,
            )
            val measurements = candidateSuites.map { definition ->
                measureSavedSuiteCoverage(ResolverTestSuite.load(definition))
            }
            val measurement = combineCoverageMeasurements(measurements)
            val observations = measurement.interactionVectorObservations()
            val observedVectorsByBatch = observations.groupBy { it.test.batchId }
                .mapValues { (_, batchObservations) -> batchObservations.mapTo(mutableSetOf(), ResolverInteractionVectorObservation::vector) }
            val candidateBatchIds = measurement.executions.map { it.test.batchId }.distinct().sorted()
            val vectorsByBatch = candidateBatchIds.associateWith { observedVectorsByBatch[it].orEmpty() }
            val universe = observations.mapTo(mutableSetOf(), ResolverInteractionVectorObservation::vector)
            val report = measurement.report()
            val categoryIds = report.metrics.map { it.id }
            val scoringStartedAt = System.nanoTime()
            val batchCoverage = buildBatchCoverageMatrix(vectorsByBatch, universe)
            val batchPairs = scoreBatchPairs(vectorsByBatch, batchCoverage)
            val exactSelection = greedyBatchSelection(vectorsByBatch, batchCoverage, optimizeFuzzyCoverage = false)
            val fuzzySelection = greedyBatchSelection(vectorsByBatch, batchCoverage, optimizeFuzzyCoverage = true)
            val budgets = listOf(1, 2, 4, 8, (vectorsByBatch.size + 1) / 2, vectorsByBatch.size).distinct().filter { it <= vectorsByBatch.size }
            val categoryBreakdownBudget = (vectorsByBatch.size + 1) / 2
            val categoryBreakdown = categoryCoverageByCategory(
                batchCoverage = batchCoverage,
                vectorsByBatch = vectorsByBatch,
                selectedBatches = fuzzySelection.take(categoryBreakdownBudget).map { it.batchId },
                categoryIds = categoryIds,
            )
            val scoringMillis = (System.nanoTime() - scoringStartedAt) / 1_000_000

            assertTrue(observations.isNotEmpty(), "The candidate pool produced no RFC-271 interaction vectors")
            assertTrue(universe.all { vector -> vector.categoryId in categoryIds }, "A vector has no RFC-271 category")
            assertTrue(batchPairs.all { it.exactJaccard in 0.0..1.0 && it.fuzzyJaccard in 0.0..1.0 })
            assertTrue(exactSelection.zipWithNext().all { (before, after) -> after.exactCovered >= before.exactCovered })
            assertTrue(fuzzySelection.zipWithNext().all { (before, after) -> after.fuzzyCoverage >= before.fuzzyCoverage })
            println(
                "RFC-271 batch selection pilot candidateSuites=${candidateSuites.map { it.id }} " +
                    "selectionUnit=whole_batch " +
                    "candidateBatches=${vectorsByBatch.size} vectorObservations=${observations.size} distinctCategoryVectors=${universe.size} " +
                    "observedCategories=${universe.groupingBy { it.categoryId }.eachCount().toSortedMap()} " +
                    "pairComparisons=${batchPairs.size} scoringMillis=$scoringMillis " +
                    "fuzzyRule=within-category; equal-weight taxonomy dimensions; categorical values exact; numeric depths use 1/(1+distance); variable participant and edge counts use greedy one-to-one fuzzy Jaccard " +
                    "closestBatchPairs=${batchPairs.take(5).joinToString { "${it.left}<->${it.right}[exact=${it.exactJaccard},fuzzy=${it.fuzzyJaccard}]" }} " +
                    "exactSelectionOrder=${exactSelection.joinToString { it.batchId }} " +
                    "fuzzySelectionOrder=${fuzzySelection.joinToString { it.batchId }} " +
                    "exactGreedy=${budgetSummary(exactSelection, budgets, universe.size)} " +
                    "fuzzyGreedy=${budgetSummary(fuzzySelection, budgets, universe.size)} " +
                    "fuzzyCategoryBreakdownAtBudget=$categoryBreakdownBudget:$categoryBreakdown " +
                    "categoryCoverageUsedForSelection=pooled_across_all_categories"
            )
        }

    @Test
    @Tag("Rfc271CoverageExperiment")
    @EnabledIfEnvironmentVariable(named = RFC_271_COVERAGE_EXPERIMENT_ENV, matches = "true")
    fun `selection compares ten generated batches with the default prefix`(): Unit =
        runTest {
            val definition = ResolverTestSuiteDefinition.RESOLUTION_MIXED_VARIABLES
            val seed = 271_828L
            val availableBatches = 20
            val retainedBatches = 10
            val candidate = CoverageCandidate.Generated(
                definition = definition,
                seed = seed,
                counts = TestCaseCount(schemas = availableBatches, queriesPerSchema = 5),
            )
            val measurement = measureCoverage(candidate)
            val observations = measurement.interactionVectorObservations()
            val observedVectorsByBatch = observations.groupBy { it.test.batchId }
                .mapValues { (_, batchObservations) -> batchObservations.mapTo(mutableSetOf(), ResolverInteractionVectorObservation::vector) }
            val candidateBatchIds = measurement.executions.map { it.test.batchId }.distinct().sorted()
            val vectorsByBatch = candidateBatchIds.associateWith { observedVectorsByBatch[it].orEmpty() }
            val universe = observations.mapTo(mutableSetOf(), ResolverInteractionVectorObservation::vector)
            val categoryIds = measurement.report().metrics.mapTo(mutableSetOf()) { it.id }
            val batchCoverage = buildBatchCoverageMatrix(vectorsByBatch, universe)
            val d0Selection = greedyBatchSelection(vectorsByBatch, batchCoverage, optimizeFuzzyCoverage = false)
            val fuzzySelection = greedyBatchSelection(vectorsByBatch, batchCoverage, optimizeFuzzyCoverage = true)
            val baselineFirstTen = candidateBatchIds.take(retainedBatches)
            val d0Selected = d0Selection.take(retainedBatches).map { it.batchId }
            val fuzzySelected = fuzzySelection.take(retainedBatches).map { it.batchId }
            val baselineScore = scoreBatchSet(baselineFirstTen, vectorsByBatch, batchCoverage)
            val d0Score = scoreBatchSet(d0Selected, vectorsByBatch, batchCoverage)
            val fuzzyScore = scoreBatchSet(fuzzySelected, vectorsByBatch, batchCoverage)
            val vectorSimilarity = buildVectorSimilarityMatrix(universe)
            val baselineDiversity = vectorDiversity(baselineFirstTen, vectorsByBatch, vectorSimilarity)
            val d0Diversity = vectorDiversity(d0Selected, vectorsByBatch, vectorSimilarity)
            val fuzzyDiversity = vectorDiversity(fuzzySelected, vectorsByBatch, vectorSimilarity)
            val poolDiversity = vectorDiversity(candidateBatchIds, vectorsByBatch, vectorSimilarity)
            val baselinePairwiseDissimilarity = pairwiseDissimilarityScore(baselineFirstTen, vectorsByBatch, vectorSimilarity)
            val d0PairwiseDissimilarity = pairwiseDissimilarityScore(d0Selected, vectorsByBatch, vectorSimilarity)
            val fuzzyPairwiseDissimilarity = pairwiseDissimilarityScore(fuzzySelected, vectorsByBatch, vectorSimilarity)
            val vectorSupport = candidateBatchIds.flatMap { vectorsByBatch.getValue(it) }
                .groupingBy { it }
                .eachCount()
                .values
            val nearestSimilarities = vectorSimilarity.nearestSameCategorySimilarities()
            val random = Random(seed)
            val randomSelections = List(500) {
                candidateBatchIds.shuffled(random).take(retainedBatches)
            }
            val randomScores = randomSelections.map { scoreBatchSet(it, vectorsByBatch, batchCoverage) }
            val randomDissimilarityScores = randomSelections.take(100)
                .map { pairwiseDissimilarityScore(it, vectorsByBatch, vectorSimilarity) }

            assertEquals(availableBatches, candidateBatchIds.size)
            assertTrue(observations.isNotEmpty(), "${candidate.label} produced no RFC-271 interaction vectors")
            assertEquals(retainedBatches, d0Selected.size)
            assertEquals(retainedBatches, fuzzySelected.size)
            assertTrue(d0Selection.zipWithNext().all { (before, after) -> after.exactCovered >= before.exactCovered })
            assertTrue(universe.all { vector -> vector.categoryId in categoryIds })
            println(
                "RFC-271 generated-batch selection candidate=${candidate.label} " +
                    "availableBatches=$availableBatches retainedBatches=$retainedBatches " +
                    "vectorObservations=${observations.size} distinctCategoryVectors=${universe.size} " +
                    "baselineFirstTen=${baselineScore.summary(universe.size)} " +
                    "baselineVectorDiversity=${baselineDiversity.summary()} " +
                    "d0Greedy=${d0Score.summary(universe.size)} selected=$d0Selected " +
                    "d0SelectedVectorDiversity=${d0Diversity.summary()} " +
                    "fuzzyGreedy=${fuzzyScore.summary(universe.size)} selected=$fuzzySelected " +
                    "fuzzyGreedyVectorDiversity=${fuzzyDiversity.summary()} " +
                    "pairwiseDissimilarity=baseline:${baselinePairwiseDissimilarity.summary()}," +
                    "d0Selected:${d0PairwiseDissimilarity.summary()},fuzzy:${fuzzyPairwiseDissimilarity.summary()} " +
                    "allTwentyVectorDiversity=${poolDiversity.summary()} " +
                    "vectorBatchSupport=once=${vectorSupport.count { it == 1 }},multiple=${vectorSupport.count { it > 1 }} " +
                    "nearestSameCategorySimilarity=p50=${nearestSimilarities.percentile(0.5)},p90=${nearestSimilarities.percentile(0.9)},max=${nearestSimilarities.maxOrNull()} " +
                    "random500MeanExact=${randomScores.map { it.exactCovered }.average()}/${universe.size} " +
                    "random500MeanFuzzy=${randomScores.map { it.fuzzyCoverage }.average()} " +
                    "random500BestExact=${randomScores.maxOf { it.exactCovered }}/${universe.size} " +
                    "random500BestFuzzy=${randomScores.maxOf { it.fuzzyCoverage }} " +
                    "random100MeanPairwiseDissimilarity=${randomDissimilarityScores.map { it.total }.average()} " +
                    "random100BestPairwiseDissimilarity=${randomDissimilarityScores.maxOf { it.total }}"
            )
        }

    @Test
    fun `coverage measurement keeps intermediate query fragment rows without treating them as standalone validated results`() =
        runTest {
            val suite = ResolverTestSuite.load(ResolverTestSuiteDefinition.QUERY_FRAGMENT)
            val (batch, fragmentRow) =
                suite.batches.firstNotNullOf { batch ->
                    measureSavedSuiteCoverage(suite, selectedBatchId = batch.id).rows.firstOrNull { it.resultPath.contains("queryFragment-") }?.let { batch to it }
                }
            val selectedCase = ResolverTestCaseId(suite.definition.id, batch.id, fragmentRow.test.queryId)

            val measurement = measureSavedSuiteCoverage(suite, selectedCase = selectedCase)

            assertEquals(1, measurement.executions.size)
            assertTrue(measurement.rows.any { it.test.queryId == selectedCase.query && it.resultPath.contains("queryFragment-") })
        }
}

private data class BatchPairScore(
    val left: String,
    val right: String,
    val exactJaccard: Double,
    val fuzzyJaccard: Double,
)

private data class BatchSelectionStep(
    val batchId: String,
    val selectedBatches: List<String>,
    val exactCovered: Int,
    val fuzzyCoverage: Double,
    val exactMarginal: Int,
    val fuzzyMarginal: Double,
)

private data class BatchMarginalGain(
    val batchId: String,
    val exactGain: Int,
    val fuzzyGain: Double,
)

private data class BatchCoverageMatrix(
    val orderedUniverse: List<ResolverInteractionVector>,
    val coverageByBatch: Map<String, DoubleArray>,
)

private data class VectorSimilarityMatrix(
    val vectors: List<ResolverInteractionVector>,
    val similarities: Array<DoubleArray>,
) {
    private val indexByVector = vectors.withIndex().associate { it.value to it.index }

    fun nearestSameCategorySimilarities(): List<Double> =
        vectors.indices.map { index ->
            val categoryId = vectors[index].categoryId
            vectors.indices.asSequence()
                .filter { other -> other != index && vectors[other].categoryId == categoryId }
                .map { other -> similarities[index][other] }
                .maxOrNull() ?: 0.0
        }

    fun indexOf(vector: ResolverInteractionVector): Int = checkNotNull(indexByVector[vector])
}

private data class VectorDiversityScores(
    val d0: Int,
    val d1: Double,
    val d2: Double,
    val similarityAdjustedD0: Double,
    val similarityAdjustedD1: Double,
    val similarityAdjustedD2: Double,
) {
    fun summary(): String =
        "D0=$d0,D1=$d1,D2=$d2,similarityAdjustedD0=$similarityAdjustedD0," +
            "similarityAdjustedD1=$similarityAdjustedD1,similarityAdjustedD2=$similarityAdjustedD2"
}

private data class PairwiseDissimilarityScore(
    val distinctVectors: Int,
    val total: Double,
    val mean: Double,
) {
    fun summary(): String = "vectors=$distinctVectors,sum=$total,mean=$mean"
}

private data class BatchSetScore(
    val exactCovered: Int,
    val fuzzyCoverage: Double,
) {
    fun summary(totalVectors: Int): String = "$exactCovered/$totalVectors,fuzzy=$fuzzyCoverage"
}

private fun scoreBatchSet(
    selectedBatches: List<String>,
    vectorsByBatch: Map<String, Set<ResolverInteractionVector>>,
    batchCoverage: BatchCoverageMatrix,
): BatchSetScore {
    val selectedVectors = selectedBatches.flatMapTo(mutableSetOf()) { batchId -> vectorsByBatch.getValue(batchId) }
    val memberships = DoubleArray(batchCoverage.orderedUniverse.size)
    selectedBatches.forEach { batchId ->
        batchCoverage.coverageByBatch.getValue(batchId).forEachIndexed { index, value ->
            memberships[index] = maxOf(memberships[index], value)
        }
    }
    return BatchSetScore(
        exactCovered = selectedVectors.size,
        fuzzyCoverage = if (memberships.isEmpty()) 0.0 else memberships.average(),
    )
}

/** Builds a symmetric category-scoped similarity matrix over exact vector types. */
private fun buildVectorSimilarityMatrix(universe: Set<ResolverInteractionVector>): VectorSimilarityMatrix {
    val vectors = universe.sortedWith(compareBy<ResolverInteractionVector> { it.categoryId }.thenBy { it.toString() })
    val similarities = Array(vectors.size) { DoubleArray(vectors.size) }
    vectors.indices.forEach { leftIndex ->
        similarities[leftIndex][leftIndex] = 1.0
        for (rightIndex in leftIndex + 1 until vectors.size) {
            val similarity = vectors[leftIndex].similarityTo(vectors[rightIndex])
            similarities[leftIndex][rightIndex] = similarity
            similarities[rightIndex][leftIndex] = similarity
        }
    }
    return VectorSimilarityMatrix(vectors, similarities)
}

/**
 * Computes exact Hill numbers over vector-to-batch support frequencies and a similarity-adjusted
 * version using each vector's similarity-weighted neighborhood mass.
 */
private fun vectorDiversity(
    selectedBatches: List<String>,
    vectorsByBatch: Map<String, Set<ResolverInteractionVector>>,
    matrix: VectorSimilarityMatrix,
): VectorDiversityScores {
    val supportCounts = IntArray(matrix.vectors.size)
    selectedBatches.distinct().forEach { batchId ->
        vectorsByBatch.getValue(batchId).forEach { vector -> supportCounts[matrix.indexOf(vector)] += 1 }
    }
    val activeIndices = supportCounts.indices.filter { supportCounts[it] > 0 }
    if (activeIndices.isEmpty()) return VectorDiversityScores(0, 0.0, 0.0, 0.0, 0.0, 0.0)
    val totalSupport = activeIndices.sumOf { supportCounts[it] }.toDouble()
    val probabilities = DoubleArray(matrix.vectors.size) { index -> supportCounts[index] / totalSupport }
    val neighborhoodMass = DoubleArray(matrix.vectors.size) { index ->
        activeIndices.sumOf { otherIndex -> matrix.similarities[index][otherIndex] * probabilities[otherIndex] }
    }
    val exactD1 = exp(-activeIndices.sumOf { index -> probabilities[index] * ln(probabilities[index]) })
    val exactD2 = 1.0 / activeIndices.sumOf { index -> probabilities[index] * probabilities[index] }
    val similarityAdjustedD0 = activeIndices.sumOf { index -> probabilities[index] / neighborhoodMass[index] }
    val similarityAdjustedD1 = exp(-activeIndices.sumOf { index -> probabilities[index] * ln(neighborhoodMass[index]) })
    val similarityAdjustedD2 = 1.0 / activeIndices.sumOf { index -> probabilities[index] * neighborhoodMass[index] }
    return VectorDiversityScores(
        d0 = activeIndices.size,
        d1 = exactD1,
        d2 = exactD2,
        similarityAdjustedD0 = similarityAdjustedD0,
        similarityAdjustedD1 = similarityAdjustedD1,
        similarityAdjustedD2 = similarityAdjustedD2,
    )
}

private fun pairwiseDissimilarityScore(
    selectedBatches: List<String>,
    vectorsByBatch: Map<String, Set<ResolverInteractionVector>>,
    matrix: VectorSimilarityMatrix,
): PairwiseDissimilarityScore {
    val vectors = selectedBatches.flatMapTo(mutableSetOf()) { batchId -> vectorsByBatch.getValue(batchId) }
        .map(matrix::indexOf)
    var total = 0.0
    for (leftIndex in vectors.indices) {
        for (rightIndex in leftIndex + 1 until vectors.size) {
            total += 1.0 - matrix.similarities[vectors[leftIndex]][vectors[rightIndex]]
        }
    }
    val pairCount = vectors.size.toLong() * (vectors.size - 1) / 2
    return PairwiseDissimilarityScore(
        distinctVectors = vectors.size,
        total = total,
        mean = if (pairCount == 0L) 0.0 else total / pairCount,
    )
}

private fun List<Double>.percentile(quantile: Double): Double? {
    if (isEmpty()) return null
    require(quantile in 0.0..1.0)
    return sorted()[(quantile * (size - 1)).toInt()]
}

private fun buildBatchCoverageMatrix(
    vectorsByBatch: Map<String, Set<ResolverInteractionVector>>,
    universe: Set<ResolverInteractionVector>,
): BatchCoverageMatrix {
    val orderedUniverse = universe.sortedWith(compareBy<ResolverInteractionVector> { it.categoryId }.thenBy { it.toString() })
    val batchVectorsByCategory = vectorsByBatch.mapValues { (_, vectors) ->
        vectors.groupBy { it.categoryId }.mapValues { (_, categoryVectors) -> categoryVectors.toSet() }
    }
    val coverageByBatch = vectorsByBatch.keys.associateWith { batchId ->
        val vectorsByCategory = batchVectorsByCategory.getValue(batchId)
        DoubleArray(orderedUniverse.size) { index ->
            val vector = orderedUniverse[index]
            bestVectorSimilarity(vector, vectorsByCategory[vector.categoryId].orEmpty())
        }
    }
    return BatchCoverageMatrix(orderedUniverse, coverageByBatch)
}

private fun scoreBatchPairs(
    vectorsByBatch: Map<String, Set<ResolverInteractionVector>>,
    batchCoverage: BatchCoverageMatrix,
): List<BatchPairScore> {
    val pairs = mutableListOf<BatchPairScore>()
    val names = vectorsByBatch.keys.sorted()
    for (leftIndex in names.indices) {
        for (rightIndex in leftIndex + 1 until names.size) {
            val leftName = names[leftIndex]
            val rightName = names[rightIndex]
            val left = vectorsByBatch.getValue(leftName)
            val right = vectorsByBatch.getValue(rightName)
            val exactUnion = left union right
            if (exactUnion.isEmpty()) continue
            val exactJaccard = if (exactUnion.isEmpty()) 1.0 else left.intersect(right).size.toDouble() / exactUnion.size
            val leftCoverage = batchCoverage.coverageByBatch.getValue(leftName)
            val rightCoverage = batchCoverage.coverageByBatch.getValue(rightName)
            var fuzzyIntersection = 0.0
            var fuzzyUnion = 0.0
            batchCoverage.orderedUniverse.indices.forEach { index ->
                val leftMembership = leftCoverage[index]
                val rightMembership = rightCoverage[index]
                fuzzyIntersection += minOf(leftMembership, rightMembership)
                fuzzyUnion += maxOf(leftMembership, rightMembership)
            }
            val fuzzyJaccard = if (fuzzyUnion == 0.0) 1.0 else fuzzyIntersection / fuzzyUnion
            pairs += BatchPairScore(leftName, rightName, exactJaccard, fuzzyJaccard)
        }
    }
    return pairs.sortedWith(compareByDescending<BatchPairScore> { it.fuzzyJaccard }.thenByDescending { it.exactJaccard }.thenBy { it.left }.thenBy { it.right })
}

private fun greedyBatchSelection(
    vectorsByBatch: Map<String, Set<ResolverInteractionVector>>,
    batchCoverage: BatchCoverageMatrix,
    optimizeFuzzyCoverage: Boolean,
): List<BatchSelectionStep> {
    val orderedUniverse = batchCoverage.orderedUniverse
    if (orderedUniverse.isEmpty()) return emptyList()
    val memberships = DoubleArray(orderedUniverse.size)
    val selectedVectors = mutableSetOf<ResolverInteractionVector>()
    val remainingBatches = vectorsByBatch.keys.toSortedSet()
    val selectedBatches = mutableListOf<String>()
    val selections = mutableListOf<BatchSelectionStep>()

    while (remainingBatches.isNotEmpty()) {
        val bestMarginal = remainingBatches.map { batchId ->
            val candidateVectors = vectorsByBatch.getValue(batchId)
            val candidateCoverage = batchCoverage.coverageByBatch.getValue(batchId)
            var fuzzyGain = 0.0
            orderedUniverse.forEachIndexed { index, _ ->
                fuzzyGain += maxOf(memberships[index], candidateCoverage[index]) - memberships[index]
            }
            val exactGain = candidateVectors.count { it !in selectedVectors }
            BatchMarginalGain(batchId, exactGain, fuzzyGain)
        }.minWithOrNull(
            compareByDescending<BatchMarginalGain> { if (optimizeFuzzyCoverage) it.fuzzyGain else it.exactGain.toDouble() }
                .thenByDescending { if (optimizeFuzzyCoverage) it.exactGain else 0 }
                .thenBy { it.batchId }
        ) ?: break
        val bestBatch = bestMarginal.batchId

        val candidateVectors = vectorsByBatch.getValue(bestBatch)
        val candidateCoverage = batchCoverage.coverageByBatch.getValue(bestBatch)
        val beforeFuzzyCoverage = memberships.sum() / orderedUniverse.size
        val beforeExactCoverage = selectedVectors.size
        orderedUniverse.forEachIndexed { index, _ ->
            memberships[index] = maxOf(memberships[index], candidateCoverage[index])
        }
        selectedVectors += candidateVectors
        selectedBatches += bestBatch
        remainingBatches -= bestBatch
        selections += BatchSelectionStep(
            batchId = bestBatch,
            selectedBatches = selectedBatches.toList(),
            exactCovered = selectedVectors.size,
            fuzzyCoverage = memberships.sum() / orderedUniverse.size,
            exactMarginal = selectedVectors.size - beforeExactCoverage,
            fuzzyMarginal = memberships.sum() / orderedUniverse.size - beforeFuzzyCoverage,
        )
    }
    return selections
}

private fun bestVectorSimilarity(
    vector: ResolverInteractionVector,
    candidates: Set<ResolverInteractionVector>,
): Double = candidates.maxOfOrNull(vector::similarityTo) ?: 0.0

private fun ResolverInteractionVector.similarityTo(other: ResolverInteractionVector): Double {
    if (categoryId != other.categoryId) return 0.0
    if (relationships.isNotEmpty() || other.relationships.isNotEmpty()) {
        if (participants.isNotEmpty() || other.participants.isNotEmpty()) return 0.0
        return fuzzyJaccard(relationships, other.relationships, ResolverInteractionRelationship::similarityTo)
    }

    if (participants.all { it.role == "member" } && other.participants.all { it.role == "member" }) {
        return fuzzyJaccard(participants, other.participants, ResolverInteractionParticipant::similarityTo)
    }

    if (participants.size != other.participants.size || participants.zip(other.participants).any { (left, right) -> left.role != right.role }) return 0.0
    return participants.zip(other.participants).map { (left, right) -> left.similarityTo(right) }.average()
}

private fun ResolverInteractionParticipant.similarityTo(other: ResolverInteractionParticipant): Double {
    if (role != other.role) return 0.0
    val leftTaxonomy = taxonomy
    val rightTaxonomy = other.taxonomy
    return when {
        leftTaxonomy == null && rightTaxonomy == null -> 1.0
        leftTaxonomy == null || rightTaxonomy == null -> 0.0
        else -> leftTaxonomy.similarityTo(rightTaxonomy)
    }
}

private fun ResolverInteractionRelationship.similarityTo(other: ResolverInteractionRelationship): Double {
    val providerSimilarity = taxonomySimilarity(providerTaxonomy, other.providerTaxonomy)
    val consumerSimilarity = taxonomySimilarity(consumerTaxonomy, other.consumerTaxonomy)
    val exactRelationshipMatches = listOf(
        providerSource == other.providerSource,
        providerOutcome == other.providerOutcome,
    ).count { it }
    return (providerSimilarity * 9 + consumerSimilarity * 9 + exactRelationshipMatches) / 20.0
}

private fun taxonomySimilarity(
    left: ResolverOccurrenceSignature?,
    right: ResolverOccurrenceSignature?,
): Double =
    when {
        left == null && right == null -> 1.0
        left == null || right == null -> 0.0
        else -> left.similarityTo(right)
    }

private fun <T> fuzzyJaccard(
    left: List<T>,
    right: List<T>,
    similarity: (T, T) -> Double,
): Double {
    if (left.isEmpty() || right.isEmpty()) return if (left.isEmpty() && right.isEmpty()) 1.0 else 0.0
    val intersection = (greedyFuzzyIntersection(left, right, similarity) + greedyFuzzyIntersection(right, left, similarity)) / 2.0
    val union = left.size + right.size - intersection
    return if (union == 0.0) 1.0 else intersection / union
}

private fun <T> greedyFuzzyIntersection(
    left: List<T>,
    right: List<T>,
    similarity: (T, T) -> Double,
): Double {
    val matchedRight = BooleanArray(right.size)
    var intersection = 0.0
    for (leftValue in left) {
        var bestRight = -1
        var bestSimilarity = 0.0
        for (rightIndex in right.indices) {
            if (matchedRight[rightIndex]) continue
            val candidateSimilarity = similarity(leftValue, right[rightIndex])
            if (candidateSimilarity > bestSimilarity) {
                bestRight = rightIndex
                bestSimilarity = candidateSimilarity
            }
        }
        if (bestRight >= 0) {
            matchedRight[bestRight] = true
            intersection += bestSimilarity
        }
    }
    return intersection
}

private fun ResolverOccurrenceSignature.similarityTo(other: ResolverOccurrenceSignature): Double {
    val leftProviderDepth = maxProviderPathDepth
    val rightProviderDepth = other.maxProviderPathDepth
    val exactMatches = listOf(
        resolutionRole == other.resolutionRole,
        locus == other.locus,
        stampClass == other.stampClass,
        bindingSources == other.bindingSources,
        argumentState == other.argumentState,
        declaredOutputShape == other.declaredOutputShape,
        outputKind == other.outputKind,
    ).count { it }
    val listDepthSimilarity = 1.0 / (1 + kotlin.math.abs(listAncestorCount - other.listAncestorCount))
    val providerDepthSimilarity = when {
        leftProviderDepth == null && rightProviderDepth == null -> 1.0
        leftProviderDepth == null || rightProviderDepth == null -> 0.0
        else -> 1.0 / (1 + kotlin.math.abs(leftProviderDepth - rightProviderDepth))
    }
    return (exactMatches + listDepthSimilarity + providerDepthSimilarity) / 9.0
}

private fun categoryCoverageByCategory(
    batchCoverage: BatchCoverageMatrix,
    vectorsByBatch: Map<String, Set<ResolverInteractionVector>>,
    selectedBatches: List<String>,
    categoryIds: List<String>,
): Map<String, String> {
    val selectedVectors = selectedBatches.flatMapTo(mutableSetOf()) { batchId -> vectorsByBatch.getValue(batchId) }
    val selectedMemberships = DoubleArray(batchCoverage.orderedUniverse.size)
    selectedBatches.forEach { batchId ->
        val coverage = batchCoverage.coverageByBatch.getValue(batchId)
        coverage.forEachIndexed { index, membership -> selectedMemberships[index] = maxOf(selectedMemberships[index], membership) }
    }
    return categoryIds.associateWith { categoryId ->
        val categoryIndexes = batchCoverage.orderedUniverse.indices.filter { batchCoverage.orderedUniverse[it].categoryId == categoryId }
        val fuzzyCoverage = if (categoryIndexes.isEmpty()) 0.0 else categoryIndexes.sumOf { selectedMemberships[it] } / categoryIndexes.size
        val exactCovered = categoryIndexes.count { batchCoverage.orderedUniverse[it] in selectedVectors }
        "$exactCovered/${categoryIndexes.size}:$fuzzyCoverage"
    }.toSortedMap()
}

private fun budgetSummary(
    selection: List<BatchSelectionStep>,
    budgets: List<Int>,
    totalVectors: Int,
): String =
    budgets.joinToString(separator = ";") { budget ->
        val step = selection[budget - 1]
        "k=$budget exact=${step.exactCovered}/$totalVectors fuzzy=${step.fuzzyCoverage} " +
            "marginalExact=${step.exactMarginal} marginalFuzzy=${step.fuzzyMarginal}"
    }
