package viaduct.engine.runtime2.coverage

import viaduct.engine.runtime2.propertytest.PropertyTestJson

/** Builds per-case evidence from the same definitions used to calculate metric support. */
internal class ResolverCoverageObligationEvidenceBuilder(
    private val measurement: ResolverCoverageMeasurement,
) {
    fun build(): List<ResolverCoverageObligationEvidence> =
        resolverCoverageMetricCatalog.mapNotNull { definition ->
            val evidence = definition.evidence(measurement)
            if (evidence.isEmpty) {
                null
            } else {
                ResolverCoverageObligationEvidence(definition.id, evidence.locations())
            }
        }
}

private fun ResolverCoverageEvidence.locations(): Set<CoverageEvidenceLocation> =
    buildSet {
        rows.forEach { row -> add(CoverageEvidenceLocation(row.fieldCoordinate, row.resultPath.stableOccurrenceCoordinate())) }
        dependencyGroups.flatten().forEach { edge ->
            add(CoverageEvidenceLocation(edge.providerFieldCoordinate, edge.provider.occurrenceCoordinate?.stableOccurrenceCoordinate()))
            add(CoverageEvidenceLocation(edge.consumerFieldCoordinate, edge.consumer.occurrenceCoordinate?.stableOccurrenceCoordinate()))
        }
    }

private fun String.stableOccurrenceCoordinate(): String {
    val coordinate = PropertyTestJson.read<Map<String, Any?>>(this).toMutableMap()
    val root = requireNotNull(coordinate["root"] as? String)
    coordinate["root"] = root.split("+").joinToString("+") { it.replace(Regex("-[0-9]+$"), "") }
    return PropertyTestJson.write(coordinate)
}
