package viaduct.engine.runtime2.coverage

import viaduct.engine.runtime2.propertytest.PropertyTestJson
import viaduct.engine.runtime2.propertytest.ResolverTestShardIds

/** A self-contained HTML page of both candidates' batches, cases and behavior combinations; a batch in both is drawn once. */
object SuiteCoverageComparisonPage {
    private const val TEMPLATE = "viaduct/engine/runtime2/coverage/suite-coverage-comparison.html"
    private val catalogById = resolverCoverageMetricCatalog.associateBy { it.id }

    fun render(
        comparison: SuiteCoverageComparison,
        leftBatches: List<CandidateBatch>,
        rightBatches: List<CandidateBatch>,
    ): String {
        val left = sideBatches(comparison.left, leftBatches)
        val right = sideBatches(comparison.right, rightBatches)
        val shared = left.keys intersect right.keys
        val batches =
            left.map { (key, batch) -> batch + ("side" to if (key in shared) "both" else "a") } +
                right.filterKeys { it !in shared }.values.map { it + ("side" to "b") }
        val data =
            mapOf(
                "a" to comparison.left.label,
                "b" to comparison.right.label,
                "sameCost" to comparison.sameCost,
                "vectorD0" to mapOf("a" to comparison.left.vectorD0, "b" to comparison.right.vectorD0),
                "inapplicable" to mapOf("a" to comparison.left.inapplicableObligations.sorted(), "b" to comparison.right.inapplicableObligations.sorted()),
                "resolverDiversity" to mapOf("a" to comparison.left.diversityData(), "b" to comparison.right.diversityData()),
                "behaviors" to
                    comparison.left.report.metrics.map { metric ->
                        val definition = catalogById.getValue(metric.id)
                        mapOf("id" to metric.id, "code" to definition.code, "description" to metric.description, "evidence" to metric.evidenceKind.name, "core" to definition.core)
                    },
                // Open-ended attributes exist only for observed values, so each side can name attributes the other lacks.
                "attributes" to (comparison.left.report.attributes + comparison.right.report.attributes).distinctBy { it.id }.map { mapOf("id" to it.id, "description" to it.description) },
                "batches" to batches,
            )
        val template = requireNotNull(PropertyTestJson.readResourceBytesOrNull(TEMPLATE)) { "Missing resource $TEMPLATE" }.decodeToString()
        return template.replace("__DATA__", PropertyTestJson.write(data).replace("</", "<\\/"))
    }

    // Batch and content IDs are hashes, so equal keys on both sides name the same generated batch.
    private fun sideBatches(
        coverage: CandidateCoverage,
        batches: List<CandidateBatch>,
    ): Map<CandidateBatchKey, Map<String, Any>> {
        val casesByBatch = coverage.report.analysis.signatures.groupBy { it.test.batchId.substringAfterLast('/') }
        val vectorD0ByBatch = coverage.batchVectorD0.mapKeys { (batchId, _) -> batchId.substringAfterLast('/') }
        return batches.associate { candidate ->
            CandidateBatchKey(candidate.id, candidate.ids) to
                mapOf(
                    "id" to candidate.id,
                    "d0" to (vectorD0ByBatch[candidate.id] ?: 0),
                    "schemaId" to candidate.ids.schema,
                    "sdl" to candidate.batch.schema.sdl,
                    "registry" to
                        mapOf(
                            "id" to candidate.ids.registry,
                            "objectFragments" to
                                candidate.batch.registry.objectFragmentSources
                                    .filterValues(String::isNotEmpty)
                                    .mapKeys { (coordinate, _) -> "${coordinate.typeName}.${coordinate.fieldName}" },
                        ),
                    "queries" to candidate.ids.queries.zip(candidate.batch.queries).map { (id, query) -> mapOf("id" to id, "source" to query.source) },
                    "cases" to
                        casesByBatch[candidate.id].orEmpty().map { case ->
                            mapOf("query" to case.test.queryId, "obligations" to case.obligations.sorted(), "attributes" to case.attributes.sorted())
                        },
                )
        }
    }

    /** Resolver-fingerprint D0-D2 are displayed as diagnostics; they do not drive vector D0 ranking. */
    private fun CandidateCoverage.diversityData(): Map<String, Any> {
        val batchDiversity = report.analysis.batchDiversity

        fun average(value: (ResolverBatchDiversity) -> Double): Double = if (batchDiversity.isEmpty()) 0.0 else batchDiversity.sumOf(value) / batchDiversity.size
        return mapOf(
            "batchCount" to batchDiversity.size,
            "d0" to average { it.d0.toDouble() },
            "d1" to average { it.d1 },
            "d2" to average { it.d2 },
        )
    }

    private data class CandidateBatchKey(val id: String, val ids: ResolverTestShardIds)
}
