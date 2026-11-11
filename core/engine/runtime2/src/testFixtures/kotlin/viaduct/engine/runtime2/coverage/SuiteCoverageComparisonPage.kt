package viaduct.engine.runtime2.coverage

import viaduct.engine.runtime2.propertytest.PropertyTestJson
import viaduct.engine.runtime2.propertytest.ResolverTestShardIds

/** A self-contained HTML page of both candidates' batches, cases and behavior combinations; a batch in both is drawn once. */
object SuiteCoverageComparisonPage {
    private const val TEMPLATE = "viaduct/engine/runtime2/coverage/suite-coverage-comparison.html"

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
                "behaviors" to comparison.left.report.metrics.map { mapOf("id" to it.id, "description" to it.description) },
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
        return batches.associate { candidate ->
            CandidateBatchKey(candidate.id, candidate.ids) to
                mapOf(
                    "id" to candidate.id,
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

    private data class CandidateBatchKey(val id: String, val ids: ResolverTestShardIds)
}
