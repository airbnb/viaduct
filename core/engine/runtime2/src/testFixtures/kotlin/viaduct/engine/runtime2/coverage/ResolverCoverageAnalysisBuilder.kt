package viaduct.engine.runtime2.coverage

internal const val HIGH_SIMILARITY_THRESHOLD = 0.8

internal data class CoverageScopeSummaries(
    val suite: CoverageScopeSummary,
    val registries: List<CoverageScopeSummary>,
    val schemas: List<CoverageScopeSummary>,
)

/** Counts cases and covered obligations at the suite, registry, and schema scopes. */
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
            testCases = signatures.size,
            coveredObligations = signatures.flatMapTo(sortedSetOf()) { it.obligations },
        )
}

internal class ResolverCoverageAnalysisBuilder(
    private val measurement: ResolverCoverageMeasurement,
) {
    fun build(): ResolverCoverageAnalysis {
        val signatures = ResolverCoverageSignatureBuilder(measurement).build()
        val scopes = CoverageScopeSummarizer().summarize(signatures)
        val resolverFingerprints = ResolverFingerprintBuilder.build(measurement.rows)
        val suiteResolverFingerprints = ResolverSuiteFingerprintBuilder.build(resolverFingerprints)
        val resolverSimilarity = ResolverFingerprintSimilarityFinder(HIGH_SIMILARITY_THRESHOLD).find(suiteResolverFingerprints)

        return ResolverCoverageAnalysis(
            modelVersion = measurement.modelVersion,
            signatures = signatures,
            suite = scopes.suite,
            registries = scopes.registries,
            schemas = scopes.schemas,
            similarityThreshold = HIGH_SIMILARITY_THRESHOLD,
            resolverFingerprints = resolverFingerprints,
            suiteResolverFingerprints = suiteResolverFingerprints,
            batchDiversity = ResolverBatchDiversityCalculator.summarize(measurement.executions, resolverFingerprints),
            highlySimilarResolvers = resolverSimilarity.similarities,
            resolverSimilarityComparisons = resolverSimilarity.exactComparisons,
        )
    }
}

private class ResolverCoverageSignatureBuilder(
    private val measurement: ResolverCoverageMeasurement,
) {
    fun build(): List<ResolverCoverageSignature> {
        val executionsByTest = measurement.executions.groupBy { it.test }
        val rowsByTest = measurement.rows.groupBy { it.test }
        val dependenciesByTest = measurement.dependencies.groupBy { it.execution.test }

        return executionsByTest.keys.sortedWith(coverageTestIdComparator).map { test ->
            val testExecutions = executionsByTest.getValue(test)
            val testRows = rowsByTest[test].orEmpty()
            val testDependencies = dependenciesByTest[test].orEmpty()
            val testMeasurement =
                ResolverCoverageMeasurement(
                    executions = testExecutions,
                    rows = testRows,
                    modelVersion = measurement.modelVersion,
                    dependencies = testDependencies,
                    checkerOccurrences = measurement.checkerOccurrences.filter { it.execution.test == test },
                )
            val evidence =
                ResolverCoverageObligationEvidenceBuilder(
                    measurement = testMeasurement,
                ).build()
            ResolverCoverageSignature(
                test = test,
                obligations = evidence.mapTo(sortedSetOf()) { it.obligationId },
                attributes = resolverCoverageAttributes(testMeasurement)
                    .filter { it.support.tests > 0 }
                    .mapTo(sortedSetOf()) { it.id },
                evidence = evidence,
            )
        }
    }
}

internal val coverageTestIdComparator =
    compareBy<CoverageTestId> { it.batchId }
        .thenBy { it.schemaId }
        .thenBy { it.registryId }
        .thenBy { it.queryId }
