package viaduct.engine.runtime2.coverage

import com.fasterxml.jackson.annotation.JsonProperty
import viaduct.engine.api.ResolverType

/** One observed resolver behavior profile for a stable saved test case. */
data class ResolverCoverageSignature(
    val test: CoverageTestId,
    val obligations: Set<String>,
    val attributes: Set<String>,
    val evidence: List<ResolverCoverageObligationEvidence>,
)

/** Occurrences that demonstrate one obligation in this test case. */
data class ResolverCoverageObligationEvidence(
    val obligationId: String,
    val locations: Set<CoverageEvidenceLocation>,
)

data class CoverageEvidenceLocation(
    val fieldCoordinate: String,
    val occurrenceCoordinate: String?,
)

enum class CoverageScopeLevel { SUITE, REGISTRY, SCHEMA }

data class CoverageScopeSummary(
    val level: CoverageScopeLevel,
    val batchId: String?,
    val registryId: String?,
    val schemaId: String?,
    val testCases: Int,
    val coveredObligations: Set<String>,
)

/** A resolver's RFC-271 taxonomy signature, with identity and location deliberately excluded. */
data class ResolverOccurrenceSignature(
    val resolutionRole: ResolutionRole,
    val locus: ResolverLocus,
    val stampClass: StampClass,
    val listAncestorCount: Int,
    val bindingSources: Set<ResolverBindingSource>,
    val maxProviderPathDepth: Int?,
    val maxArgumentPathDepth: Int = 0,
    val argumentState: ArgumentState,
    val declaredOutputShape: DeclaredOutputShape,
    val outputKind: OutputKind,
)

/** Stable identity of one resolver definition observed in a run and batch. */
data class ResolverDefinitionId(
    val runId: String,
    val batchId: String,
    val registryId: String,
    val resolverName: String,
    val resolverType: ResolverType,
)

/** Distinct behavior signatures observed for one resolver definition across its batch queries. */
data class ResolverFingerprint(
    val id: ResolverDefinitionId,
    val fieldCoordinates: Set<String>,
    val signatures: Set<ResolverOccurrenceSignature>,
    val signatureSupport: List<ResolverSignatureSupport>,
    val supportingTests: Set<CoverageTestId>,
)

/** Test cases that support one distinct occurrence signature in a resolver fingerprint. */
data class ResolverSignatureSupport(
    val signature: ResolverOccurrenceSignature,
    val testCases: Set<CoverageTestId>,
)

/** D0-D2 summarize the distribution of resolver fingerprints within one batch. */
data class ResolverBatchDiversity(
    val runId: String,
    val batchId: String,
    val resolverDefinitions: Int,
    val d0: Int,
    val d1: Double,
    val d2: Double,
) {
    /** Diversity divided by resolver count so batches of different sizes can be compared. */
    @get:JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val normalizedD0: Double? get() = normalize(d0.toDouble())
    @get:JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val normalizedD1: Double? get() = normalize(d1)
    @get:JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val normalizedD2: Double? get() = normalize(d2)

    private fun normalize(value: Double): Double? = resolverDefinitions.takeIf { it > 0 }?.let { value / it }
}

/** One resolver's combined taxonomy signature across every batch in a run. */
data class ResolverSuiteFingerprint(
    val runId: String,
    val resolverName: String,
    val resolverType: ResolverType,
    val fieldCoordinates: Set<String>,
    val signatures: Set<ResolverOccurrenceSignature>,
    val supportingBatches: Set<String>,
    val supportingRegistries: Set<String>,
    val supportingTests: Set<CoverageTestId>,
)

/** A suite-wide close Jaccard pair. Similarity is exact even when candidate discovery is indexed. */
data class ResolverFingerprintSimilarity(
    val left: ResolverSuiteFingerprint,
    val right: ResolverSuiteFingerprint,
    val jaccardSimilarity: Double,
    val sharedSignatures: Set<ResolverOccurrenceSignature>,
) {
    @get:JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val leftOnlySignatures: Set<ResolverOccurrenceSignature> get() = left.signatures - sharedSignatures
    @get:JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val rightOnlySignatures: Set<ResolverOccurrenceSignature> get() = right.signatures - sharedSignatures
}

/** Result of removing one test case, used to reject reductions that lose measured coverage. */
data class CoverageRemovalAssessment(
    val test: CoverageTestId,
    val noLongerCoveredObligations: Set<String>,
    val noLongerCoveredAttributes: Set<String>,
    val noLongerCoveredResolverSignatures: Set<ResolverOccurrenceSignature>,
) {
    @get:JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val coveragePreserved: Boolean
        get() = noLongerCoveredObligations.isEmpty() && noLongerCoveredAttributes.isEmpty() && noLongerCoveredResolverSignatures.isEmpty()
}

data class ResolverCoverageAnalysis(
    val modelVersion: Int,
    val signatures: List<ResolverCoverageSignature>,
    val suite: CoverageScopeSummary,
    val registries: List<CoverageScopeSummary>,
    val schemas: List<CoverageScopeSummary>,
    val similarityThreshold: Double,
    val resolverFingerprints: List<ResolverFingerprint> = emptyList(),
    val suiteResolverFingerprints: List<ResolverSuiteFingerprint> = emptyList(),
    val batchDiversity: List<ResolverBatchDiversity> = emptyList(),
    val highlySimilarResolvers: List<ResolverFingerprintSimilarity> = emptyList(),
    val resolverSimilarityComparisons: Int = 0,
) {
    init {
        require(modelVersion == RESOLVER_COVERAGE_MODEL_VERSION) { "Incompatible coverage model" }
        require(signatures.map { it.test }.distinct().size == signatures.size) { "Duplicate test signature" }
        require(suite.level == CoverageScopeLevel.SUITE)
        require(registries.all { it.level == CoverageScopeLevel.REGISTRY })
        require(schemas.all { it.level == CoverageScopeLevel.SCHEMA })
        require(similarityThreshold in 0.0..1.0)
        require(resolverFingerprints.map { it.id }.distinct().size == resolverFingerprints.size) { "Duplicate resolver fingerprint" }
        require(suiteResolverFingerprints.map { Triple(it.runId, it.resolverName, it.resolverType) }.distinct().size == suiteResolverFingerprints.size) { "Duplicate suite resolver fingerprint" }
        require(batchDiversity.map { it.runId to it.batchId }.distinct().size == batchDiversity.size) { "Duplicate batch diversity summary" }
        require(resolverSimilarityComparisons >= highlySimilarResolvers.size)
    }
}

/** A test can be removed only when its obligations and taxonomy attributes remain supported. */
fun ResolverCoverageAnalysis.assessTestCaseRemoval(test: CoverageTestId): CoverageRemovalAssessment {
    val removed = requireNotNull(signatures.singleOrNull { it.test == test }) { "Unknown or ambiguous test case: $test" }
    val remaining = signatures.filterNot { it.test == test }
    val remainingObligations = remaining.flatMapTo(mutableSetOf()) { it.obligations }
    val remainingAttributes = remaining.flatMapTo(mutableSetOf()) { it.attributes }
    val removedResolverSignatures = resolverFingerprints.flatMap { fingerprint ->
        fingerprint.signatureSupport.filter { test in it.testCases }.map { it.signature }
    }.toSet()
    val remainingResolverSignatures = resolverFingerprints.flatMap { fingerprint ->
        fingerprint.signatureSupport.filterNot { support -> support.testCases == setOf(test) }.map { it.signature }
    }.toSet()
    return CoverageRemovalAssessment(
        test = test,
        noLongerCoveredObligations = removed.obligations - remainingObligations,
        noLongerCoveredAttributes = removed.attributes - remainingAttributes,
        noLongerCoveredResolverSignatures = removedResolverSignatures - remainingResolverSignatures,
    )
}

data class CoverageCaseChange(
    val test: CoverageTestId,
    val status: CoverageCaseStatus,
    val baselineObligations: Set<String>,
    val candidateObligations: Set<String>,
    val baselineAttributes: Set<String>,
    val candidateAttributes: Set<String>,
    val baselineEvidence: List<ResolverCoverageObligationEvidence>,
    val candidateEvidence: List<ResolverCoverageObligationEvidence>,
    val newlyCovered: Set<String>,
    val noLongerCovered: Set<String>,
)

enum class CoverageCaseStatus { ADDED, REMOVED, CHANGED, UNCHANGED }

data class CoverageCaseEditSummary(
    val added: Int,
    val removed: Int,
    val changedBehavior: Int,
    val unchanged: Int,
)

data class CoverageScopeChange(
    val level: CoverageScopeLevel,
    val batchId: String?,
    val registryId: String?,
    val schemaId: String?,
    val baselineTestCases: Int,
    val candidateTestCases: Int,
    val baselineObligations: Set<String>,
    val candidateObligations: Set<String>,
    val newlyCovered: Set<String>,
    val noLongerCovered: Set<String>,
)

data class ResolverCoverageAnalysisComparison(
    val modelVersion: Int,
    val suite: CoverageScopeChange,
    val cases: List<CoverageCaseChange>,
    val caseEdits: CoverageCaseEditSummary,
    val schemas: List<CoverageScopeChange>,
)

/** Builds an analysis from a complete correctness-checked resolver measurement. */
fun ResolverCoverageMeasurement.analyze(): ResolverCoverageAnalysis = ResolverCoverageAnalysisBuilder(this).build()

/** Compares two complete analyses by the obligations their saved cases support. */
fun ResolverCoverageAnalysis.compareCandidate(candidate: ResolverCoverageAnalysis): ResolverCoverageAnalysisComparison = ResolverCoverageCandidateComparator(this, candidate).compare()
