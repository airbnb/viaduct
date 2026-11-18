package viaduct.engine.runtime2.coverage

import java.io.Reader
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import viaduct.engine.api.ResolverType

/** RFC-4180 quoting preserves structured paths, including embedded quotes and line breaks. */
object ResolverCoverageCsv {
    private val header = listOf(
        "run_id",
        "execution_id",
        "batch_id",
        "schema_id",
        "registry_id",
        "query_id",
        "occurrence_id",
        "result_path",
        "field_coordinate",
        "resolver_name",
        "resolver_type",
        "resolution_role",
        "locus",
        "stamp_class",
        "list_ancestor_count",
        "binding_sources",
        "max_provider_path_depth",
        "argument_state",
        "declared_output_shape",
        "output_kind",
    ) + ObjectFragmentShape.CSV_COLUMNS

    fun write(
        rows: List<ResolverCoverageRow>,
        output: Appendable
    ) {
        CSVPrinter(output, CSVFormat.RFC4180).apply {
            printRecord(header)
            rows.forEach { row ->
                printRecord(
                    listOf(
                        row.runId,
                        row.executionId,
                        row.batchId,
                        row.schemaId,
                        row.registryId,
                        row.queryId,
                        row.occurrenceId,
                        row.resultPath,
                        row.fieldCoordinate,
                        row.resolverName,
                        row.resolverType.name,
                        row.resolutionRole.name,
                        row.locus.name,
                        row.stampClass.name,
                        row.listAncestorCount,
                        row.bindingSources.sortedBy { it.ordinal }.joinToString("|") { it.name },
                        row.maxProviderPathDepth ?: "",
                        row.argumentState.name,
                        row.declaredOutputShape.name,
                        row.outputKind.name,
                    ) + row.objectFragment.csvValues()
                )
            }
            flush()
        }
    }

    fun read(input: Reader): List<ResolverCoverageRow> =
        CSVFormat.RFC4180.parse(input).use { parser ->
            val records = parser.records
            require(records.isNotEmpty() && records.first().toList() == header) { "Unexpected resolver coverage CSV header" }
            records.drop(1).map { record ->
                require(record.size() == header.size) { "Unexpected resolver coverage CSV row width" }
                ResolverCoverageRow(
                    runId = record[0],
                    executionId = record[1],
                    batchId = record[2],
                    schemaId = record[3],
                    registryId = record[4],
                    queryId = record[5],
                    occurrenceId = record[6],
                    resultPath = record[7],
                    fieldCoordinate = record[8],
                    resolverName = record[9],
                    resolverType = ResolverType.valueOf(record[10]),
                    resolutionRole = ResolutionRole.valueOf(record[11]),
                    locus = ResolverLocus.valueOf(record[12]),
                    stampClass = StampClass.valueOf(record[13]),
                    listAncestorCount = record[14].toInt(),
                    bindingSources = record[15]
                        .takeIf(String::isNotEmpty)
                        ?.split('|')
                        ?.map(ResolverBindingSource::valueOf)
                        ?.toSet()
                        ?: emptySet(),
                    maxProviderPathDepth = record[16].takeIf(String::isNotEmpty)?.toInt(),
                    argumentState = ArgumentState.valueOf(record[17]),
                    declaredOutputShape = DeclaredOutputShape.valueOf(record[18]),
                    outputKind = OutputKind.valueOf(record[19]),
                    objectFragment = ObjectFragmentShape.fromCsvValues((20 until header.size).map { record[it].toInt() }),
                )
            }
        }
}
