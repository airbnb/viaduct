package viaduct.engine.runtime2.coverage

internal class ResolverCoverageAnalysisBuilder(
    private val measurement: ResolverCoverageMeasurement,
) {
    fun build(): ResolverCoverageAnalysis {
        val signatures = ResolverCoverageSignatureBuilder(measurement).build()
        val scopes = CoverageScopeSummarizer().summarize(signatures)

        return ResolverCoverageAnalysis(
            modelVersion = measurement.modelVersion,
            signatures = signatures,
            suite = scopes.suite,
            registries = scopes.registries,
            schemas = scopes.schemas,
            similarityThreshold = HIGH_SIMILARITY_THRESHOLD,
            highlySimilarProfiles =
                CoverageProfileSimilarityFinder(HIGH_SIMILARITY_THRESHOLD)
                    .find(signatures),
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
