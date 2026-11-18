package viaduct.engine.runtime2.coverage

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/** Builds one behavior fingerprint per observed resolver definition. */
internal object ResolverFingerprintBuilder {
    fun build(rows: List<ResolverCoverageRow>): List<ResolverFingerprint> =
        rows.groupBy { row ->
            ResolverDefinitionId(
                runId = row.runId,
                batchId = row.batchId,
                registryId = row.registryId,
                resolverName = row.resolverName,
                resolverType = row.resolverType,
            )
        }.map { (id, resolverRows) ->
            ResolverFingerprint(
                id = id,
                fieldCoordinates = resolverRows.mapTo(sortedSetOf()) { it.fieldCoordinate },
                signatures = resolverRows.mapTo(mutableSetOf(), ResolverCoverageRow::behaviorSignature),
                signatureSupport = resolverRows.groupBy(ResolverCoverageRow::behaviorSignature).map { (signature, rowsForSignature) ->
                    ResolverSignatureSupport(signature, rowsForSignature.map { it.test }.toSortedSet(coverageTestIdComparator))
                }.sortedBy { signatureKey(it.signature) },
                supportingTests = resolverRows.mapTo(mutableSetOf()) { it.test },
            )
        }.sortedWith(resolverFingerprintComparator)
}

private fun ResolverCoverageRow.behaviorSignature() =
    ResolverOccurrenceSignature(
        resolutionRole = resolutionRole,
        locus = locus,
        stampClass = stampClass,
        listAncestorCount = listAncestorCount,
        bindingSources = bindingSources,
        maxProviderPathDepth = maxProviderPathDepth,
        argumentState = argumentState,
        declaredOutputShape = declaredOutputShape,
        outputKind = outputKind,
    )

/** Computes D0-D2 once per run and batch, weighting each resolver definition once. */
internal object ResolverBatchDiversityCalculator {
    fun summarize(
        executions: List<CoverageExecution>,
        fingerprints: List<ResolverFingerprint>,
    ): List<ResolverBatchDiversity> {
        val fingerprintsByBatch = fingerprints.groupBy { it.id.runId to it.id.batchId }
        return executions.map { it.runId to it.test.batchId }.distinct().sortedWith(compareBy({ it.first }, { it.second })).map { (runId, batchId) ->
            val batchFingerprints = fingerprintsByBatch[runId to batchId].orEmpty()
            val frequencies = batchFingerprints.groupingBy { it.signatures }.eachCount().values
            if (frequencies.isEmpty()) {
                ResolverBatchDiversity(runId, batchId, resolverDefinitions = 0, d0 = 0, d1 = 0.0, d2 = 0.0)
            } else {
                val probabilities = frequencies.map { it.toDouble() / batchFingerprints.size }
                val entropy = -probabilities.sumOf { probability -> probability * ln(probability) }
                val concentration = probabilities.sumOf { it * it }
                ResolverBatchDiversity(
                    runId = runId,
                    batchId = batchId,
                    resolverDefinitions = batchFingerprints.size,
                    d0 = frequencies.size,
                    d1 = exp(entropy),
                    d2 = 1.0 / concentration,
                )
            }
        }
    }
}

/** Combines each resolver's observations across its batches before suite-wide comparisons. */
internal object ResolverSuiteFingerprintBuilder {
    fun build(fingerprints: List<ResolverFingerprint>): List<ResolverSuiteFingerprint> =
        fingerprints.groupBy { Triple(it.id.runId, it.id.resolverName, it.id.resolverType) }
            .map { (identity, observations) ->
                ResolverSuiteFingerprint(
                    runId = identity.first,
                    resolverName = identity.second,
                    resolverType = identity.third,
                    fieldCoordinates = observations.flatMapTo(sortedSetOf()) { it.fieldCoordinates },
                    signatures = observations.flatMapTo(mutableSetOf()) { it.signatures },
                    supportingBatches = observations.mapTo(sortedSetOf()) { it.id.batchId },
                    supportingRegistries = observations.mapTo(sortedSetOf()) { it.id.registryId },
                    supportingTests = observations.flatMapTo(sortedSetOf(coverageTestIdComparator)) { it.supportingTests },
                )
            }.sortedWith(resolverSuiteFingerprintComparator)
}

internal data class ResolverSimilaritySearchResult(
    val similarities: List<ResolverFingerprintSimilarity>,
    val candidatePairs: Int,
    val exactComparisons: Int,
)

/**
 * Finds all resolver pairs at or above a Jaccard threshold without comparing every pair.
 * A frequency-ordered prefix index omits pairs that cannot reach the threshold; a set-size
 * upper bound also removes pairs before exact intersection work.
 */
internal class ResolverFingerprintSimilarityFinder(
    private val threshold: Double,
) {
    init {
        require(threshold in 0.0..1.0)
    }

    fun find(fingerprints: List<ResolverSuiteFingerprint>): ResolverSimilaritySearchResult {
        val candidates = candidatePairs(fingerprints)
        var exactComparisons = 0
        val similarities = candidates.mapNotNull { pair ->
            val left = fingerprints[pair.left]
            val right = fingerprints[pair.right]
            val leftSize = left.signatures.size
            val rightSize = right.signatures.size
            val largest = maxOf(leftSize, rightSize)
            val sizeUpperBound = if (largest == 0) 1.0 else min(leftSize, rightSize).toDouble() / largest
            if (sizeUpperBound < threshold) return@mapNotNull null

            exactComparisons++
            val shared = left.signatures.intersect(right.signatures)
            val unionSize = leftSize + rightSize - shared.size
            val similarity = if (unionSize == 0) 1.0 else shared.size.toDouble() / unionSize
            if (similarity + 1e-12 < threshold) return@mapNotNull null
            ResolverFingerprintSimilarity(
                left = left,
                right = right,
                jaccardSimilarity = similarity,
                sharedSignatures = shared,
            )
        }.sortedWith(resolverSimilarityComparator)
        return ResolverSimilaritySearchResult(similarities, candidates.size, exactComparisons)
    }

    private fun candidatePairs(fingerprints: List<ResolverSuiteFingerprint>): Set<IndexPair> {
        val candidates = mutableSetOf<IndexPair>()
        if (threshold == 0.0) {
            for (left in fingerprints.indices) {
                for (right in left + 1 until fingerprints.size) {
                    if (!sameResolver(fingerprints[left], fingerprints[right])) candidates += IndexPair(left, right)
                }
            }
        } else {
            val documentFrequency = mutableMapOf<ResolverOccurrenceSignature, Int>()
            fingerprints.forEach { fingerprint ->
                fingerprint.signatures.forEach { signature ->
                    documentFrequency[signature] = (documentFrequency[signature] ?: 0) + 1
                }
            }
            val postings = mutableMapOf<ResolverOccurrenceSignature, MutableList<Int>>()
            fingerprints.forEachIndexed { index, fingerprint ->
                val orderedSignatures = fingerprint.signatures.sortedWith(
                    compareBy<ResolverOccurrenceSignature> { documentFrequency.getValue(it) }
                        .thenBy(::signatureKey)
                )
                val prefixLength = prefixLength(orderedSignatures.size)
                orderedSignatures.take(prefixLength).forEach { signature ->
                    postings.getOrPut(signature) { mutableListOf() }.forEach { previousIndex ->
                        val previousSize = fingerprints[previousIndex].signatures.size
                        val largest = maxOf(previousSize, orderedSignatures.size)
                        val upperBound = if (largest == 0) 1.0 else min(previousSize, orderedSignatures.size).toDouble() / largest
                        if (upperBound >= threshold && !sameResolver(fingerprints[previousIndex], fingerprints[index])) {
                            candidates += IndexPair(previousIndex, index)
                        }
                    }
                    postings.getOrPut(signature) { mutableListOf() } += index
                }
            }
            val emptyProfiles = fingerprints.indices.filter { fingerprints[it].signatures.isEmpty() }
            for (leftOffset in emptyProfiles.indices) {
                for (rightOffset in leftOffset + 1 until emptyProfiles.size) {
                    val leftIndex = emptyProfiles[leftOffset]
                    val rightIndex = emptyProfiles[rightOffset]
                    if (!sameResolver(fingerprints[leftIndex], fingerprints[rightIndex])) {
                        candidates += IndexPair(leftIndex, rightIndex)
                    }
                }
            }
        }
        return candidates.sortedWith(compareBy<IndexPair> { it.left }.thenBy { it.right }).toSet()
    }

    private fun sameResolver(
        left: ResolverSuiteFingerprint,
        right: ResolverSuiteFingerprint
    ): Boolean =
        left.runId == right.runId &&
            left.resolverType == right.resolverType &&
            left.fieldCoordinates == right.fieldCoordinates

    private fun prefixLength(setSize: Int): Int {
        if (setSize == 0) return 0
        return (setSize - kotlin.math.ceil(threshold * setSize).toInt() + 1).coerceIn(1, setSize)
    }

    private data class IndexPair(val left: Int, val right: Int)
}

private val resolverFingerprintComparator =
    compareBy<ResolverFingerprint> { it.id.runId }
        .thenBy { it.id.batchId }
        .thenBy { it.id.registryId }
        .thenBy { it.id.resolverName }
        .thenBy { it.id.resolverType.name }

private val resolverSuiteFingerprintComparator =
    compareBy<ResolverSuiteFingerprint> { it.runId }
        .thenBy { it.resolverName }
        .thenBy { it.resolverType.name }

private val resolverSimilarityComparator =
    compareBy<ResolverFingerprintSimilarity> { it.left.runId }
        .thenBy { it.left.resolverName }
        .thenBy { it.right.runId }
        .thenBy { it.right.resolverName }

private fun signatureKey(signature: ResolverOccurrenceSignature): String =
    listOf(
        signature.resolutionRole.name,
        signature.locus.name,
        signature.stampClass.name,
        signature.listAncestorCount.toString(),
        signature.bindingSources.map { it.name }.sorted().joinToString("|"),
        signature.maxProviderPathDepth?.toString().orEmpty(),
        signature.argumentState.name,
        signature.declaredOutputShape.name,
        signature.outputKind.name,
    ).joinToString("\u0000")
