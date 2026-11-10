package viaduct.engine.runtime2.coverage

/** Compares saved cases and schemas without mixing diversity changes with obligation coverage. */
internal class ResolverCoverageCandidateComparator(
    private val baseline: ResolverCoverageAnalysis,
    private val candidate: ResolverCoverageAnalysis,
) {
    fun compare(): ResolverCoverageAnalysisComparison {
        require(baseline.modelVersion == candidate.modelVersion) { "Cannot compare different coverage model versions" }

        val baselineByTest = baseline.signatures.associateBy { it.test }
        val candidateByTest = candidate.signatures.associateBy { it.test }
        val baselineSuiteObligations = baselineByTest.values.flatMapTo(sortedSetOf()) { it.obligations }
        val candidateSuiteObligations = candidateByTest.values.flatMapTo(sortedSetOf()) { it.obligations }
        val cases = compareCases(baselineByTest, candidateByTest)

        return ResolverCoverageAnalysisComparison(
            modelVersion = baseline.modelVersion,
            suite = compareSuite(baseline, candidate, baselineSuiteObligations, candidateSuiteObligations),
            cases = cases,
            caseEdits =
                CoverageCaseEditSummary(
                    added = cases.count { it.status == CoverageCaseStatus.ADDED },
                    removed = cases.count { it.status == CoverageCaseStatus.REMOVED },
                    changedBehavior = cases.count { it.status == CoverageCaseStatus.CHANGED },
                    unchanged = cases.count { it.status == CoverageCaseStatus.UNCHANGED },
                ),
            schemas = compareSchemas(baseline, candidate, baselineSuiteObligations, candidateSuiteObligations),
        )
    }

    private fun compareSuite(
        baseline: ResolverCoverageAnalysis,
        candidate: ResolverCoverageAnalysis,
        baselineSuiteObligations: Set<String>,
        candidateSuiteObligations: Set<String>,
    ): CoverageScopeChange =
        CoverageScopeChangeBuilder.compare(
            level = CoverageScopeLevel.SUITE,
            batchId = null,
            registryId = null,
            schemaId = null,
            baseline = baseline.signatures,
            candidate = candidate.signatures,
            baselineSuiteObligations = baselineSuiteObligations,
            candidateSuiteObligations = candidateSuiteObligations,
        )

    private fun compareCases(
        baseline: Map<CoverageTestId, ResolverCoverageSignature>,
        candidate: Map<CoverageTestId, ResolverCoverageSignature>,
    ): List<CoverageCaseChange> =
        (baseline.keys + candidate.keys).distinct().sortedWith(coverageTestIdComparator).map { test ->
            val before = baseline[test]
            val after = candidate[test]
            val baselineObligations = before?.obligations.orEmpty()
            val candidateObligations = after?.obligations.orEmpty()
            val baselineAttributes = before?.attributes.orEmpty()
            val candidateAttributes = after?.attributes.orEmpty()
            val status =
                when {
                    before == null -> CoverageCaseStatus.ADDED
                    after == null -> CoverageCaseStatus.REMOVED
                    before.obligations != after.obligations ||
                        before.attributes != after.attributes ||
                        before.evidence != after.evidence -> CoverageCaseStatus.CHANGED
                    else -> CoverageCaseStatus.UNCHANGED
                }
            CoverageCaseChange(
                test = test,
                status = status,
                baselineObligations = baselineObligations,
                candidateObligations = candidateObligations,
                baselineAttributes = baselineAttributes,
                candidateAttributes = candidateAttributes,
                baselineEvidence = before?.evidence.orEmpty(),
                candidateEvidence = after?.evidence.orEmpty(),
                newlyCovered = candidateObligations - baselineObligations,
                noLongerCovered = baselineObligations - candidateObligations,
            )
        }

    private fun compareSchemas(
        baseline: ResolverCoverageAnalysis,
        candidate: ResolverCoverageAnalysis,
        baselineSuiteObligations: Set<String>,
        candidateSuiteObligations: Set<String>,
    ): List<CoverageScopeChange> {
        val baselineBySchema = baseline.signatures.groupBy { it.test.schemaId }
        val candidateBySchema = candidate.signatures.groupBy { it.test.schemaId }
        return (baselineBySchema.keys + candidateBySchema.keys).distinct().sorted().map { schemaId ->
            CoverageScopeChangeBuilder.compare(
                level = CoverageScopeLevel.SCHEMA,
                batchId = null,
                registryId = null,
                schemaId = schemaId,
                baseline = baselineBySchema[schemaId].orEmpty(),
                candidate = candidateBySchema[schemaId].orEmpty(),
                baselineSuiteObligations = baselineSuiteObligations,
                candidateSuiteObligations = candidateSuiteObligations,
            )
        }
    }
}

private object CoverageScopeChangeBuilder {
    fun compare(
        level: CoverageScopeLevel,
        batchId: String?,
        registryId: String?,
        schemaId: String?,
        baseline: List<ResolverCoverageSignature>,
        candidate: List<ResolverCoverageSignature>,
        baselineSuiteObligations: Set<String>,
        candidateSuiteObligations: Set<String>,
    ): CoverageScopeChange {
        val before = baseline.flatMapTo(sortedSetOf()) { it.obligations }
        val after = candidate.flatMapTo(sortedSetOf()) { it.obligations }
        return CoverageScopeChange(
            level = level,
            batchId = batchId,
            registryId = registryId,
            schemaId = schemaId,
            baseline = CoverageDiversityCalculator.summarize(baseline),
            candidate = CoverageDiversityCalculator.summarize(candidate),
            baselineObligations = before,
            candidateObligations = after,
            newlyCovered = after - baselineSuiteObligations,
            noLongerCovered = before - candidateSuiteObligations,
        )
    }
}
