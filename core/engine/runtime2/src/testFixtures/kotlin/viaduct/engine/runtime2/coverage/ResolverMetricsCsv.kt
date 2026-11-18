package viaduct.engine.runtime2.coverage

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter

/** RFC-271 batch scores and suite-wide high-similarity resolver pairs. */
object ResolverMetricsCsv {
    private val header = listOf(
        "run_id",
        "batch_id",
        "metric",
        "value",
        "normalized_value",
        "resolver_count",
        "left_run_id",
        "left_batch_id",
        "left_registry_id",
        "left_resolver_name",
        "left_resolver_type",
        "left_field_coordinate",
        "right_run_id",
        "right_batch_id",
        "right_registry_id",
        "right_resolver_name",
        "right_resolver_type",
        "right_field_coordinate",
        "left_supporting_test_count",
        "left_signature_count",
        "left_unique_signature_count",
        "right_supporting_test_count",
        "right_signature_count",
        "right_unique_signature_count",
        "shared_signature_count",
    )

    fun write(
        analysis: ResolverCoverageAnalysis,
        output: Appendable,
    ) = writeAll(listOf(analysis), output)

    /** Writes multiple candidates in one file; run IDs keep their batch rows distinct. */
    fun writeAll(
        analyses: Collection<ResolverCoverageAnalysis>,
        output: Appendable,
    ) {
        CSVPrinter(output, CSVFormat.RFC4180).use { printer ->
            printer.printRecord(header)
            analyses.forEach { analysis ->
                analysis.batchDiversity.forEach { batch ->
                    listOf(
                        Triple("D0", batch.d0.toString(), batch.normalizedD0),
                        Triple("D1", batch.d1.toString(), batch.normalizedD1),
                        Triple("D2", batch.d2.toString(), batch.normalizedD2),
                    ).forEach { (metric, value, normalizedValue) ->
                        printer.printRecord(
                            listOf<Any?>(
                                batch.runId,
                                batch.batchId,
                                metric,
                                value,
                                normalizedValue?.toString().orEmpty(),
                                batch.resolverDefinitions,
                            ) + List(header.size - 6) { "" }
                        )
                    }
                }
                analysis.highlySimilarResolvers.forEach { pair ->
                    printer.printRecord(
                        listOf<Any?>(
                            "",
                            "",
                            "Jaccard",
                            pair.jaccardSimilarity,
                            "",
                            "",
                            pair.left.runId,
                            pair.left.supportingBatches.sorted().joinToString("|"),
                            pair.left.supportingRegistries.sorted().joinToString("|"),
                            pair.left.resolverName,
                            pair.left.resolverType.name,
                            pair.left.fieldCoordinates.sorted().joinToString("|"),
                            pair.right.runId,
                            pair.right.supportingBatches.sorted().joinToString("|"),
                            pair.right.supportingRegistries.sorted().joinToString("|"),
                            pair.right.resolverName,
                            pair.right.resolverType.name,
                            pair.right.fieldCoordinates.sorted().joinToString("|"),
                            pair.left.supportingTests.size,
                            pair.left.signatures.size,
                            pair.leftOnlySignatures.size,
                            pair.right.supportingTests.size,
                            pair.right.signatures.size,
                            pair.rightOnlySignatures.size,
                            pair.sharedSignatures.size,
                        )
                    )
                }
            }
            printer.flush()
        }
    }
}
