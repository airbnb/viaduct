package viaduct.engine.runtime2.coverage

import kotlin.math.exp
import kotlin.math.ln

internal const val HIGH_SIMILARITY_THRESHOLD = 0.8

internal data class CoverageScopeSummaries(
    val suite: CoverageScopeSummary,
    val registries: List<CoverageScopeSummary>,
    val schemas: List<CoverageScopeSummary>,
)

/** Builds suite, registry, and schema summaries from the same saved-case signatures. */
internal class CoverageScopeSummarizer {
    fun summarize(signatures: List<ResolverCoverageSignature>): CoverageScopeSummaries =
        CoverageScopeSummaries(
            suite = summarizeScope(CoverageScopeLevel.SUITE, null, null, null, signatures),
            registries = summarizeRegistries(signatures),
            schemas = summarizeSchemas(signatures),
        )

    private fun summarizeRegistries(signatures: List<ResolverCoverageSignature>): List<CoverageScopeSummary> =
        signatures.groupBy { it.test.batchId to it.test.registryId }
            .toSortedMap(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .map { (key, group) -> summarizeScope(CoverageScopeLevel.REGISTRY, key.first, key.second, null, group) }

    private fun summarizeSchemas(signatures: List<ResolverCoverageSignature>): List<CoverageScopeSummary> =
        signatures.groupBy { it.test.schemaId }
            .toSortedMap()
            .map { (schemaId, group) -> summarizeScope(CoverageScopeLevel.SCHEMA, null, null, schemaId, group) }

    private fun summarizeScope(
        level: CoverageScopeLevel,
        batchId: String?,
        registryId: String?,
        schemaId: String?,
        signatures: List<ResolverCoverageSignature>,
    ): CoverageScopeSummary =
        CoverageScopeSummary(
            level = level,
            batchId = batchId,
            registryId = registryId,
            schemaId = schemaId,
            diversity = CoverageDiversityCalculator.summarize(signatures),
            coveredObligations = signatures.flatMapTo(sortedSetOf()) { it.obligations },
        )
}

/** Computes signature frequencies and Hill diversity numbers. */
internal object CoverageDiversityCalculator {
    fun summarize(signatures: List<ResolverCoverageSignature>): CoverageDiversitySummary {
        val frequencies = CoverageSignatureProfiles.count(signatures)
        if (signatures.isEmpty()) return CoverageDiversitySummary(0, 0, 0, 0.0, 0.0, 0.0, emptyList())

        val probabilities = frequencies.map { it.testCases.toDouble() / signatures.size }
        val shannonEntropy = -probabilities.sumOf { probability -> probability * ln(probability) }
        val simpsonConcentration = probabilities.sumOf { it * it }
        return CoverageDiversitySummary(
            testCases = signatures.size,
            distinctSignatures = frequencies.size,
            d0 = frequencies.size,
            d1 = exp(shannonEntropy),
            d2 = 1.0 / simpsonConcentration,
            dominantSignatureShare = frequencies.first().testCases.toDouble() / signatures.size,
            frequencies = frequencies,
        )
    }
}

/** Counts equal profiles once per saved test case and returns them in a stable order. */
internal object CoverageSignatureProfiles {
    fun count(signatures: List<ResolverCoverageSignature>): List<CoverageSignatureFrequency> =
        signatures.groupBy { it.obligations }.entries
            .map { (obligations, cases) ->
                CoverageSignatureFrequency(
                    obligations = obligations.toSortedSet(),
                    testCases = cases.size,
                    caseIds = cases.map { it.test }.sortedWith(coverageTestIdComparator),
                )
            }
            .sortedWith(compareByDescending<CoverageSignatureFrequency> { it.testCases }.thenBy { signatureKey(it.obligations) })
}

/** Finds distinct profiles whose Jaccard similarity meets the configured threshold. */
internal class CoverageProfileSimilarityFinder(
    private val threshold: Double,
) {
    fun find(signatures: List<ResolverCoverageSignature>): List<CoverageSignatureSimilarity> {
        val profiles = CoverageSignatureProfiles.count(signatures).sortedBy { signatureKey(it.obligations) }
        return buildList {
            for (leftIndex in profiles.indices) {
                for (rightIndex in leftIndex + 1 until profiles.size) {
                    compare(profiles[leftIndex], profiles[rightIndex])?.let(::add)
                }
            }
        }
    }

    private fun compare(
        left: CoverageSignatureFrequency,
        right: CoverageSignatureFrequency,
    ): CoverageSignatureSimilarity? {
        val shared = left.obligations intersect right.obligations
        val union = left.obligations union right.obligations
        val similarity = if (union.isEmpty()) 1.0 else shared.size.toDouble() / union.size
        if (similarity < threshold) return null

        return CoverageSignatureSimilarity(
            left = left,
            right = right,
            jaccardSimilarity = similarity,
            sharedObligations = shared.toSortedSet(),
            leftOnlyObligations = (left.obligations - shared).toSortedSet(),
            rightOnlyObligations = (right.obligations - shared).toSortedSet(),
        )
    }
}

private fun signatureKey(signature: Set<String>): String = signature.sorted().joinToString("\u0000")
