package viaduct.engine.runtime2.coverage

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

data class CoverageSignatureFrequency(
    val obligations: Set<String>,
    val testCases: Int,
    val caseIds: List<CoverageTestId>,
)

/** Diversity treats each stable test case as one observation, regardless of its occurrence count. */
data class CoverageDiversitySummary(
    val testCases: Int,
    val distinctSignatures: Int,
    val d0: Int,
    val d1: Double,
    val d2: Double,
    val dominantSignatureShare: Double,
    val frequencies: List<CoverageSignatureFrequency>,
)

enum class CoverageScopeLevel { SUITE, REGISTRY, SCHEMA }

data class CoverageScopeSummary(
    val level: CoverageScopeLevel,
    val batchId: String?,
    val registryId: String?,
    val schemaId: String?,
    val diversity: CoverageDiversitySummary,
    val coveredObligations: Set<String>,
)

/** Similarity between distinct signatures, with the shared and differing obligations shown. */
data class CoverageSignatureSimilarity(
    val left: CoverageSignatureFrequency,
    val right: CoverageSignatureFrequency,
    val jaccardSimilarity: Double,
    val sharedObligations: Set<String>,
    val leftOnlyObligations: Set<String>,
    val rightOnlyObligations: Set<String>,
)

data class ResolverCoverageAnalysis(
    val modelVersion: Int,
    val signatures: List<ResolverCoverageSignature>,
    val suite: CoverageScopeSummary,
    val registries: List<CoverageScopeSummary>,
    val schemas: List<CoverageScopeSummary>,
    val similarityThreshold: Double,
    val highlySimilarProfiles: List<CoverageSignatureSimilarity>,
) {
    init {
        require(modelVersion == RESOLVER_COVERAGE_MODEL_VERSION) { "Incompatible coverage model" }
        require(signatures.map { it.test }.distinct().size == signatures.size) { "Duplicate test signature" }
        require(suite.level == CoverageScopeLevel.SUITE)
        require(registries.all { it.level == CoverageScopeLevel.REGISTRY })
        require(schemas.all { it.level == CoverageScopeLevel.SCHEMA })
        require(similarityThreshold in 0.0..1.0)
    }
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
    val baseline: CoverageDiversitySummary,
    val candidate: CoverageDiversitySummary,
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
