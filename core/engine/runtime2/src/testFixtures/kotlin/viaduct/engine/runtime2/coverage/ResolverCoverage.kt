package viaduct.engine.runtime2.coverage

import java.io.Reader
import viaduct.engine.runtime2.propertytest.PropertyTestJson
import viaduct.engine.runtime2.propertytest.ResolverTestCaseId
import viaduct.engine.runtime2.resolution.framework.ProviderCellOutcome

/** Version of the RFC-271 coverage data and companion report, independent of saved-suite storage versions. */
const val RESOLVER_COVERAGE_MODEL_VERSION = 9

enum class ResolutionRole { INVOKED, SOURCE_SUPPLIED, ARGUMENT_ERROR_NOT_INVOKED, UNKNOWN }

enum class ResolverLocus { QUERY_ROOT, LIST_ELEMENT_OBJECT, OTHER_OBJECT }

enum class StampClass { VARIABLE_FREE, OCCURRENCE_STAMPED }

/** Variable binding mechanisms used by a resolver. */
enum class ResolverBindingSource { PROVIDER, ARGUMENT, OBJECT_FIELD, QUERY_FIELD }

enum class ArgumentState { NONE, RESOLVED, CONTAINS_NULL, ERROR }

enum class DeclaredOutputShape { SCALAR, OBJECT, LIST }

enum class OutputKind { SCALAR, OBJECT, LIST, NULL, ERROR }

/** Saved batch identity is suite-scoped; registry and query IDs are batch-local. */
data class CoverageTestId(val batchId: String, val schemaId: String, val registryId: String, val queryId: String) {
    companion object {
        fun from(
            testCaseId: ResolverTestCaseId,
            schemaId: String
        ): CoverageTestId =
            CoverageTestId(
                batchId = "${testCaseId.suite}/${testCaseId.batch}",
                schemaId = schemaId,
                registryId = testCaseId.registry,
                queryId = testCaseId.query,
            )
    }
}

/** A completed execution, including reruns and executions with no resolver-bearing fields. */
data class CoverageExecution(val runId: String, val executionId: String, val test: CoverageTestId)

/** One exact occurrence, before any aggregation or bucketing. Paths are structured JSON. */
data class ResolverCoverageRow(
    val runId: String,
    val executionId: String,
    val batchId: String,
    val schemaId: String,
    val registryId: String,
    val queryId: String,
    val occurrenceId: String,
    val resultPath: String,
    val fieldCoordinate: String,
    val resolutionRole: ResolutionRole,
    val locus: ResolverLocus,
    val stampClass: StampClass,
    val listAncestorCount: Int,
    val bindingSources: Set<ResolverBindingSource>,
    val maxProviderPathDepth: Int?,
    val argumentState: ArgumentState,
    val declaredOutputShape: DeclaredOutputShape,
    val outputKind: OutputKind,
) {
    val test: CoverageTestId get() = CoverageTestId(batchId, schemaId, registryId, queryId)
    val execution: CoverageExecution get() = CoverageExecution(runId, executionId, test)

    init {
        require(listOf(runId, executionId, batchId, schemaId, registryId, queryId, occurrenceId, resultPath, fieldCoordinate).none(String::isBlank))
        require(listAncestorCount >= 0 && (maxProviderPathDepth == null || maxProviderPathDepth > 0))
    }
}

/** One observed read of a terminal provider cell by one resolver occurrence. */
data class ResolverDependencyRow(
    val execution: CoverageExecution,
    val provider: ResolverDependencyOccurrence,
    val providerSource: ResolverBindingSource,
    val providerOutcome: ProviderCellOutcome,
    val consumer: ResolverDependencyOccurrence,
    val variableName: String,
) {
    val runId: String get() = execution.runId
    val executionId: String get() = execution.executionId
    val batchId: String get() = execution.test.batchId
    val schemaId: String get() = execution.test.schemaId
    val registryId: String get() = execution.test.registryId
    val queryId: String get() = execution.test.queryId
    val providerOccurrenceId: String get() = provider.occurrenceId
    val providerFieldCoordinate: String get() = provider.fieldCoordinate
    val consumerOccurrenceId: String get() = consumer.occurrenceId
    val consumerFieldCoordinate: String get() = consumer.fieldCoordinate

    init {
        require(
            listOf(
                execution.runId,
                execution.executionId,
                execution.test.batchId,
                execution.test.schemaId,
                execution.test.registryId,
                execution.test.queryId,
                variableName,
            ).none(String::isBlank)
        )
        require(providerSource == ResolverBindingSource.OBJECT_FIELD || providerSource == ResolverBindingSource.QUERY_FIELD)
    }
}

/** One endpoint of a provider-to-consumer dependency. */
data class ResolverDependencyOccurrence(
    val occurrenceId: String,
    val fieldCoordinate: String,
    val occurrenceCoordinate: String? = null,
) {
    init {
        require(listOf(occurrenceId, fieldCoordinate).none(String::isBlank))
        require(occurrenceCoordinate == null || occurrenceCoordinate.isNotBlank())
    }
}

data class ResolverCoverageRecords(
    val occurrences: List<ResolverCoverageRow>,
    val dependencies: List<ResolverDependencyRow>,
)

/** Counts support at occurrence and saved-suite levels. */
data class CoverageSupport(
    val occurrences: Int,
    val executions: Int,
    val tests: Int,
    val registries: Int,
    val batches: Int,
    val schemas: Int,
)

enum class CoverageEvidenceKind { LOCAL_COMBINATION, RESULT_TREE_PATH, DEPENDENCY_RELATIONSHIP, EXECUTION_COEXISTENCE }

/** Rows or provider reads that demonstrate an obligation; metric support and signatures derive from this. */
internal data class ResolverCoverageEvidence(
    val rows: Set<ResolverCoverageRow> = emptySet(),
    val dependencyGroups: List<List<ResolverDependencyRow>> = emptyList(),
) {
    val isEmpty: Boolean get() = rows.isEmpty() && dependencyGroups.isEmpty()

    fun support(): CoverageSupport = if (dependencyGroups.isNotEmpty()) dependencyGroups.dependencySupport() else rows.toList().support()
}

enum class ResolverCoverageRequirementKind { OBLIGATION, ATTRIBUTE }

/** A behavior or resolver feature that a revised suite must keep demonstrating. */
data class ResolverCoverageRequirement(
    val kind: ResolverCoverageRequirementKind,
    val id: String,
    val minimumDistinctCases: Int,
) {
    init {
        require(id.isNotBlank())
        require(minimumDistinctCases > 0)
    }
}

/** Explicit per-suite requirements; no obligation set is assumed to apply to every suite. */
data class ResolverCoverageScoringCriteria(
    val requirements: Set<ResolverCoverageRequirement>,
) {
    init {
        require(requirements.isNotEmpty()) { "Scoring criteria must declare at least one suite requirement" }
        require(requirements.map { it.kind to it.id }.distinct().size == requirements.size) {
            "Scoring criteria contain duplicate requirements"
        }
    }
}

/** One named RFC-271 obligation and the support for its defining evidence. */
data class ResolverCoverageMetric(
    val id: String,
    val evidenceKind: CoverageEvidenceKind,
    val description: String,
    val support: CoverageSupport,
) {
    init {
        require(id.isNotBlank() && description.isNotBlank())
    }
}

/** A resolver attribute is descriptive data; it is not an obligation to preserve by itself. */
data class ResolverCoverageAttribute(
    val id: String,
    val description: String,
    val support: CoverageSupport,
) {
    init {
        require(id.isNotBlank() && description.isNotBlank())
    }
}

internal class ResolverCoverageMetricDefinition(
    val id: String,
    val evidenceKind: CoverageEvidenceKind,
    val description: String,
    val core: Boolean = false,
    val evidence: (ResolverCoverageMeasurement) -> ResolverCoverageEvidence,
) {
    fun measure(measurement: ResolverCoverageMeasurement): ResolverCoverageMetric = ResolverCoverageMetric(id, evidenceKind, description, evidence(measurement).support())
}

internal class ResolverCoverageAttributeDefinition(
    val id: String,
    val description: String,
    val support: (ResolverCoverageMeasurement) -> CoverageSupport,
) {
    fun measure(measurement: ResolverCoverageMeasurement): ResolverCoverageAttribute = ResolverCoverageAttribute(id, description, support(measurement))
}

private fun resolverAttribute(
    id: String,
    description: String,
    matches: (ResolverCoverageRow) -> Boolean,
) = ResolverCoverageAttributeDefinition(
    id = id,
    description = description,
    support = { measurement -> measurement.rows.filter { it.resolutionRole == ResolutionRole.INVOKED && matches(it) }.support() },
)

/** Resolver attributes are reported separately and do not enter obligation signatures or scores. */
internal val resolverCoverageAttributeDefinitions =
    buildList {
        ResolutionRole.values().forEach { role ->
            add(
                ResolverCoverageAttributeDefinition(
                    "resolution_role:${role.name.lowercase()}",
                    "A resolver occurrence has the ${role.name.lowercase().replace('_', ' ')} role.",
                ) { measurement -> measurement.rows.filter { it.resolutionRole == role }.support() }
            )
        }
        ResolverLocus.values().forEach { locus ->
            add(resolverAttribute("resolver_locus:${locus.name.lowercase()}", "An invoked resolver occurs at ${locus.name.lowercase().replace('_', ' ')}.") { it.locus == locus })
        }
        StampClass.values().forEach { stampClass ->
            add(
                resolverAttribute(
                    "stamp_class:${stampClass.name.lowercase()}",
                    "An invoked resolver uses the ${stampClass.name.lowercase().replace('_', ' ')} stamp class."
                ) { it.stampClass == stampClass }
            )
        }
        add(resolverAttribute("provider_path_depth:none", "An invoked resolver has no FromField provider path.") { it.maxProviderPathDepth == null })
        val bindingSourceValues = ResolverBindingSource.values().toList()
        for (mask in 0 until (1 shl bindingSourceValues.size)) {
            val sources = bindingSourceValues.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val sourceLabel = bindingSourcesLabel(sources)
            add(
                resolverAttribute(
                    "binding_sources:$sourceLabel",
                    if (sources.isEmpty()) {
                        "An invoked resolver has no variable binding sources."
                    } else {
                        "An invoked resolver uses exactly these binding sources: $sourceLabel."
                    },
                ) { it.bindingSources == sources }
            )
        }
        ArgumentState.values().forEach { state ->
            add(resolverAttribute("argument_state:${state.name.lowercase()}", "An invoked resolver has ${state.name.lowercase().replace('_', ' ')} arguments.") { it.argumentState == state })
        }
        DeclaredOutputShape.values().forEach { shape ->
            add(resolverAttribute("declared_output_shape:${shape.name.lowercase()}", "An invoked resolver declares a ${shape.name.lowercase()} output.") { it.declaredOutputShape == shape })
        }
        OutputKind.values().forEach { kind ->
            add(resolverAttribute("output_kind:${kind.name.lowercase()}", "An invoked resolver returns a ${kind.name.lowercase()} result.") { it.outputKind == kind })
        }
    }

internal val resolverCoverageMetricCatalog =
    listOf(
        ResolverCoverageMetricDefinition(
            id = "both_sources_on_one_occurrence",
            evidenceKind = CoverageEvidenceKind.LOCAL_COMBINATION,
            description = "One invoked occurrence has both ARGUMENT and OBJECT_FIELD bindings.",
            core = true,
            evidence = ResolverCoverageMeasurement::bothSourcesOnOneOccurrenceEvidence,
        ),
        ResolverCoverageMetricDefinition(
            id = "shared_provider_fan_out",
            evidenceKind = CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
            description = "One provider occurrence has recorded reads from at least two consumers.",
            core = true,
            evidence = ResolverCoverageMeasurement::sharedProviderFanOutEvidence,
        ),
        ResolverCoverageMetricDefinition(
            id = "multiple_provider_fan_in",
            evidenceKind = CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
            description = "One consumer occurrence has recorded reads from at least two providers.",
            core = true,
            evidence = ResolverCoverageMeasurement::multipleProviderFanInEvidence,
        ),
        ResolverCoverageMetricDefinition(
            id = "both_sources_within_one_execution",
            evidenceKind = CoverageEvidenceKind.EXECUTION_COEXISTENCE,
            description = "Argument and object-field bindings occur on invoked rows in one execution.",
            core = true,
            evidence = ResolverCoverageMeasurement::bothSourcesWithinOneExecutionEvidence,
        ),
        ResolverCoverageMetricDefinition(
            id = "successful_and_failing_branches_within_one_execution",
            evidenceKind = CoverageEvidenceKind.EXECUTION_COEXISTENCE,
            description = "One execution has invoked non-error and error outputs.",
            core = true,
            evidence = ResolverCoverageMeasurement::successfulAndFailingBranchesWithinOneExecutionEvidence,
        ),
        ResolverCoverageMetricDefinition(
            id = "list_object_field_error",
            evidenceKind = CoverageEvidenceKind.LOCAL_COMBINATION,
            description = "One invoked occurrence is in a list element, uses an object-field binding, and returns an error.",
            evidence = ResolverCoverageMeasurement::listObjectFieldErrorEvidence,
        ),
        ResolverCoverageMetricDefinition(
            id = "resolver_output_contains_descendant",
            evidenceKind = CoverageEvidenceKind.RESULT_TREE_PATH,
            description = "An invoked resolver occurrence is an ancestor of another invoked resolver in the result tree.",
            evidence = ResolverCoverageMeasurement::resolverOutputContainsDescendantEvidence,
        ),
        ResolverCoverageMetricDefinition(
            id = "descendant_reads_ancestor",
            evidenceKind = CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
            description = "A provider's owning object is an ancestor of the resolver field that reads it.",
            evidence = ResolverCoverageMeasurement::descendantReadsAncestorEvidence,
        ),
        ResolverCoverageMetricDefinition(
            id = "provider_read_crosses_list_boundary",
            evidenceKind = CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
            description = "A recorded provider read connects occurrences with different list-ancestor depths.",
            evidence = ResolverCoverageMeasurement::providerReadCrossesListBoundaryEvidence,
        ),
        ResolverCoverageMetricDefinition(
            id = "provider_dependency_diamond",
            evidenceKind = CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
            description = "Recorded provider reads form a four-edge diamond from one provider through two consumers to one consumer.",
            evidence = ResolverCoverageMeasurement::providerDependencyDiamondEvidence,
        ),
        ResolverCoverageMetricDefinition(
            id = "provider_null_or_error_reaches_consumer",
            evidenceKind = CoverageEvidenceKind.DEPENDENCY_RELATIONSHIP,
            description = "A recorded read delivers a null or error provider outcome to a consumer occurrence.",
            evidence = ResolverCoverageMeasurement::providerNullOrErrorReachesConsumerEvidence,
        ),
    )

/** RFC-271's initial interaction obligations, identified by their catalog entries. */
val RFC_271_CORE_OBLIGATION_IDS: Set<String> =
    resolverCoverageMetricCatalog.filter { it.core }.mapTo(sortedSetOf()) { it.id }

/** Versioned companion report for occurrence rows, obligations, resolver attributes, and analysis. */
data class ResolverCoverageReport(
    val modelVersion: Int,
    val executions: List<CoverageExecution>,
    val metrics: List<ResolverCoverageMetric>,
    val attributes: List<ResolverCoverageAttribute>,
    val analysis: ResolverCoverageAnalysis,
) {
    init {
        require(modelVersion == RESOLVER_COVERAGE_MODEL_VERSION) { "Incompatible coverage model" }
        require(executions.distinctBy { it.runId to it.executionId }.size == executions.size) { "Duplicate execution ID" }
        val catalog = resolverCoverageMetricCatalog.map { Triple(it.id, it.evidenceKind, it.description) }
        val reported = metrics.map { Triple(it.id, it.evidenceKind, it.description) }
        require(reported == catalog) { "Coverage metrics do not match the RFC-271 catalog" }
        val attributeIds = attributes.map { it.id }
        require(attributeIds.distinct().size == attributeIds.size) { "Duplicate resolver attribute" }
        require(resolverCoverageAttributeDefinitions.map { it.id }.all { it in attributeIds }) { "Coverage attributes omit a resolver attribute category" }
        require(attributes.filter { it.id.startsWith("list_ancestor_count:") || it.id.startsWith("provider_path_depth:") && it.id != "provider_path_depth:none" }.all { it.support.occurrences > 0 }) {
            "Open-ended resolver attributes must have observed support"
        }
        require(analysis.modelVersion == modelVersion) { "Coverage analysis uses a different model version" }
    }
}

/** Support changes for one RFC obligation when comparing a candidate with a baseline. */
data class CoverageSupportChange(
    val occurrences: Int,
    val executions: Int,
    val tests: Int,
    val registries: Int,
    val batches: Int,
    val schemas: Int,
)

data class ResolverCoverageMetricComparison(
    val id: String,
    val evidenceKind: CoverageEvidenceKind,
    val description: String,
    val baselineSupport: CoverageSupport,
    val candidateSupport: CoverageSupport,
    val supportChange: CoverageSupportChange,
) {
    val newlySupported: Boolean
        get() = baselineSupport.tests == 0 && candidateSupport.tests > 0

    val noLongerSupported: Boolean
        get() = baselineSupport.tests > 0 && candidateSupport.tests == 0
}

data class ResolverCoverageAttributeComparison(
    val id: String,
    val description: String,
    val baselineSupport: CoverageSupport,
    val candidateSupport: CoverageSupport,
    val supportChange: CoverageSupportChange,
) {
    val newlySupported: Boolean
        get() = baselineSupport.tests == 0 && candidateSupport.tests > 0

    val noLongerSupported: Boolean
        get() = baselineSupport.tests > 0 && candidateSupport.tests == 0
}

/** Distinct-case support for explicit suite requirements; no sum or hidden weight. */
data class ResolverCoverageCandidateScore(
    val obligationTestSupport: Map<String, Int>,
    val attributeTestSupport: Map<String, Int>,
    val scoringCriteria: ResolverCoverageScoringCriteria,
) {
    init {
        require(obligationTestSupport.all { (id, tests) -> id.isNotBlank() && tests >= 0 })
        require(attributeTestSupport.all { (id, tests) -> id.isNotBlank() && tests >= 0 })
    }

    val supportedObligations: Set<String>
        get() = obligationTestSupport.filterValues { it > 0 }.keys

    val unmetRequirements: Set<ResolverCoverageRequirement>
        get() =
            scoringCriteria.requirements.filterTo(linkedSetOf()) { requirement ->
                supportFor(requirement) < requirement.minimumDistinctCases
            }

    val meetsRequirements: Boolean
        get() = unmetRequirements.isEmpty()

    /** An infeasible candidate cannot win through gains in another requirement. */
    fun relationTo(other: ResolverCoverageCandidateScore): ResolverCoverageScoreRelation {
        require(scoringCriteria == other.scoringCriteria) { "Cannot compare different scoring criteria" }
        if (!meetsRequirements) return ResolverCoverageScoreRelation.INFEASIBLE
        if (!other.meetsRequirements) return ResolverCoverageScoreRelation.DOMINATES
        val noWorse = scoringCriteria.requirements.all { supportFor(it) >= other.supportFor(it) }
        val noBetter = scoringCriteria.requirements.all { supportFor(it) <= other.supportFor(it) }
        return when {
            noWorse && noBetter -> ResolverCoverageScoreRelation.EQUIVALENT
            noWorse -> ResolverCoverageScoreRelation.DOMINATES
            noBetter -> ResolverCoverageScoreRelation.DOMINATED
            else -> ResolverCoverageScoreRelation.INCOMPARABLE
        }
    }

    companion object {
        fun from(
            metrics: List<ResolverCoverageMetricComparison>,
            attributes: List<ResolverCoverageAttributeComparison>,
            criteria: ResolverCoverageScoringCriteria,
        ): ResolverCoverageCandidateScore {
            val obligationSupport = metrics.associate { it.id to it.candidateSupport.tests }
            val attributeSupport = attributes.associate { it.id to it.candidateSupport.tests }
            return ResolverCoverageCandidateScore(
                obligationTestSupport = criteria.requirements
                    .filter { it.kind == ResolverCoverageRequirementKind.OBLIGATION }
                    .map { it.id }
                    .distinct()
                    .sorted()
                    .associateWith { obligationSupport[it] ?: 0 },
                attributeTestSupport = criteria.requirements
                    .filter { it.kind == ResolverCoverageRequirementKind.ATTRIBUTE }
                    .map { it.id }
                    .distinct()
                    .sorted()
                    .associateWith { attributeSupport[it] ?: 0 },
                scoringCriteria = criteria,
            )
        }
    }

    private fun supportFor(requirement: ResolverCoverageRequirement): Int =
        when (requirement.kind) {
            ResolverCoverageRequirementKind.OBLIGATION -> obligationTestSupport[requirement.id] ?: 0
            ResolverCoverageRequirementKind.ATTRIBUTE -> attributeTestSupport[requirement.id] ?: 0
        }
}

enum class ResolverCoverageScoreRelation { INFEASIBLE, DOMINATES, DOMINATED, EQUIVALENT, INCOMPARABLE }

/** Per-obligation score reasons and diversity changes for suite builders. */
data class ResolverCoverageCandidateComparison(
    val modelVersion: Int,
    val scoringCriteria: ResolverCoverageScoringCriteria,
    val metrics: List<ResolverCoverageMetricComparison>,
    val attributes: List<ResolverCoverageAttributeComparison>,
    val analysis: ResolverCoverageAnalysisComparison,
) {
    val baselineScore: ResolverCoverageCandidateScore
        get() = ResolverCoverageCandidateScore.from(
            metrics.map { it.copy(candidateSupport = it.baselineSupport) },
            attributes.map { it.copy(candidateSupport = it.baselineSupport) },
            scoringCriteria,
        )

    val candidateScore: ResolverCoverageCandidateScore
        get() = ResolverCoverageCandidateScore.from(metrics, attributes, scoringCriteria)

    val scoreChange: ResolverCoverageScoreChange
        get() {
            val before = baselineScore
            val after = candidateScore
            return ResolverCoverageScoreChange(
                obligationTestSupport =
                    scoringCriteria.requirements
                        .filter { it.kind == ResolverCoverageRequirementKind.OBLIGATION }
                        .map { it.id }
                        .distinct()
                        .sorted()
                        .associateWith { id ->
                            after.obligationTestSupport.getValue(id) - before.obligationTestSupport.getValue(id)
                        },
                attributeTestSupport =
                    scoringCriteria.requirements
                        .filter { it.kind == ResolverCoverageRequirementKind.ATTRIBUTE }
                        .map { it.id }
                        .distinct()
                        .sorted()
                        .associateWith { id ->
                            after.attributeTestSupport.getValue(id) - before.attributeTestSupport.getValue(id)
                        },
                relation = after.relationTo(before),
                unmetRequirements = after.unmetRequirements,
            )
        }
}

data class ResolverCoverageScoreChange(
    val obligationTestSupport: Map<String, Int>,
    val attributeTestSupport: Map<String, Int>,
    val relation: ResolverCoverageScoreRelation,
    val unmetRequirements: Set<ResolverCoverageRequirement>,
)

/** Requirements and purposes that must survive when comparing a retained set of suites. */
data class ResolverCoverageSuiteSetCriteria(
    val requiredSuiteIds: Set<String>,
    val coverage: ResolverCoverageScoringCriteria,
) {
    init {
        require(requiredSuiteIds.none(String::isBlank)) { "Required suite IDs must not be blank" }
    }
}

data class ResolverCoverageSuiteSetSize(
    val suites: Int,
    val cases: Int,
    val schemas: Int,
)

enum class ResolverCoverageSuiteSetRelation {
    INFEASIBLE,
    BECOMES_FEASIBLE,
    SMALLER,
    LARGER,
    EQUIVALENT,
    TRADEOFF,
}

/** Checks suite-set requirements first, then compares the size of the retained sets. */
data class ResolverCoverageSuiteSetComparison(
    val baselineSuiteIds: Set<String>,
    val candidateSuiteIds: Set<String>,
    val criteria: ResolverCoverageSuiteSetCriteria,
    val coverage: ResolverCoverageCandidateComparison,
) {
    val addedSuiteIds: Set<String> get() = candidateSuiteIds - baselineSuiteIds
    val removedSuiteIds: Set<String> get() = baselineSuiteIds - candidateSuiteIds
    val missingRequiredSuiteIds: Set<String> get() = criteria.requiredSuiteIds - candidateSuiteIds

    val baselineSize: ResolverCoverageSuiteSetSize
        get() = size(coverage.analysis.suite.baseline, baselineSuiteIds.size, coverage.analysis.schemas.count { it.baseline.testCases > 0 })

    val candidateSize: ResolverCoverageSuiteSetSize
        get() = size(coverage.analysis.suite.candidate, candidateSuiteIds.size, coverage.analysis.schemas.count { it.candidate.testCases > 0 })

    val candidateFeasible: Boolean
        get() = missingRequiredSuiteIds.isEmpty() && coverage.candidateScore.meetsRequirements

    val baselineFeasible: Boolean
        get() = (criteria.requiredSuiteIds - baselineSuiteIds).isEmpty() && coverage.baselineScore.meetsRequirements

    val relation: ResolverCoverageSuiteSetRelation
        get() {
            if (!candidateFeasible) return ResolverCoverageSuiteSetRelation.INFEASIBLE
            if (!baselineFeasible) return ResolverCoverageSuiteSetRelation.BECOMES_FEASIBLE
            val before = baselineSize
            val after = candidateSize
            val noLarger = after.suites <= before.suites && after.cases <= before.cases && after.schemas <= before.schemas
            val noSmaller = after.suites >= before.suites && after.cases >= before.cases && after.schemas >= before.schemas
            return when {
                noLarger && after != before -> ResolverCoverageSuiteSetRelation.SMALLER
                noSmaller && after != before -> ResolverCoverageSuiteSetRelation.LARGER
                after == before -> ResolverCoverageSuiteSetRelation.EQUIVALENT
                else -> ResolverCoverageSuiteSetRelation.TRADEOFF
            }
        }

    private fun size(
        diversity: CoverageDiversitySummary,
        suiteCount: Int,
        schemaCount: Int,
    ) = ResolverCoverageSuiteSetSize(suites = suiteCount, cases = diversity.testCases, schemas = schemaCount)
}

/** Compare aggregate reports when adding or removing saved suites from the retained set. */
fun ResolverCoverageReport.compareSuiteSetCandidate(
    candidate: ResolverCoverageReport,
    baselineSuiteIds: Set<String>,
    candidateSuiteIds: Set<String>,
    criteria: ResolverCoverageSuiteSetCriteria,
): ResolverCoverageSuiteSetComparison {
    require(baselineSuiteIds.none(String::isBlank) && candidateSuiteIds.none(String::isBlank)) { "Suite IDs must not be blank" }
    require(criteria.requiredSuiteIds.all { it in baselineSuiteIds + candidateSuiteIds }) { "Criteria name an unknown required suite" }
    require(baselineSuiteIds == analysis.suiteIds()) { "Baseline suite IDs do not match the measured cases" }
    require(candidateSuiteIds == candidate.analysis.suiteIds()) { "Candidate suite IDs do not match the measured cases" }
    return ResolverCoverageSuiteSetComparison(
        baselineSuiteIds = baselineSuiteIds,
        candidateSuiteIds = candidateSuiteIds,
        criteria = criteria,
        coverage = compareCandidate(candidate, criteria.coverage),
    )
}

private fun ResolverCoverageAnalysis.suiteIds(): Set<String> = signatures.mapTo(mutableSetOf()) { it.test.batchId.substringBefore('/') }

/** Compares complete suite snapshots; the candidate replaces or edits the baseline suite. */
fun ResolverCoverageReport.compareCandidate(
    candidate: ResolverCoverageReport,
    scoringCriteria: ResolverCoverageScoringCriteria,
): ResolverCoverageCandidateComparison {
    require(modelVersion == candidate.modelVersion) { "Cannot compare different coverage model versions" }
    val baselineCatalog = metrics.map { Triple(it.id, it.evidenceKind, it.description) }
    val candidateCatalog = candidate.metrics.map { Triple(it.id, it.evidenceKind, it.description) }
    require(baselineCatalog == candidateCatalog) { "Cannot compare different coverage metric catalogs" }
    val obligationIds = metrics.mapTo(mutableSetOf()) { it.id }
    val attributeIds = (attributes + candidate.attributes).mapTo(mutableSetOf()) { it.id }
    require(
        scoringCriteria.requirements
            .filter { it.kind == ResolverCoverageRequirementKind.OBLIGATION }
            .all { it.id in obligationIds }
    ) { "Scoring criteria name an unknown obligation" }
    require(
        scoringCriteria.requirements
            .filter { it.kind == ResolverCoverageRequirementKind.ATTRIBUTE }
            .all { it.id in attributeIds || it.id.isOpenEndedResolverAttribute() }
    ) { "Scoring criteria name an unknown resolver attribute" }
    return ResolverCoverageCandidateComparison(
        modelVersion = modelVersion,
        scoringCriteria = scoringCriteria,
        metrics = metrics.zip(candidate.metrics).map { (baselineMetric, candidateMetric) ->
            val baseline = baselineMetric.support
            val candidateSupport = candidateMetric.support
            ResolverCoverageMetricComparison(
                id = baselineMetric.id,
                evidenceKind = baselineMetric.evidenceKind,
                description = baselineMetric.description,
                baselineSupport = baseline,
                candidateSupport = candidateSupport,
                supportChange = baseline.supportChangeTo(candidateSupport),
            )
        },
        attributes = compareAttributes(attributes, candidate.attributes),
        analysis = analysis.compareCandidate(candidate.analysis),
    )
}

private fun String.isOpenEndedResolverAttribute(): Boolean = Regex("(?:list_ancestor_count|provider_path_depth):[0-9]+").matches(this)

private fun compareAttributes(
    baseline: List<ResolverCoverageAttribute>,
    candidate: List<ResolverCoverageAttribute>,
): List<ResolverCoverageAttributeComparison> {
    val baselineById = baseline.associateBy { it.id }
    val candidateById = candidate.associateBy { it.id }
    return (baselineById.keys + candidateById.keys).distinct().sorted().map { id ->
        val before = baselineById[id]
        val after = candidateById[id]
        val baselineSupport = before?.support ?: ZERO_COVERAGE_SUPPORT
        val candidateSupport = after?.support ?: ZERO_COVERAGE_SUPPORT
        ResolverCoverageAttributeComparison(
            id = id,
            description = after?.description ?: requireNotNull(before).description,
            baselineSupport = baselineSupport,
            candidateSupport = candidateSupport,
            supportChange = baselineSupport.supportChangeTo(candidateSupport),
        )
    }
}

private val ZERO_COVERAGE_SUPPORT = CoverageSupport(0, 0, 0, 0, 0, 0)

private fun CoverageSupport.supportChangeTo(candidate: CoverageSupport) =
    CoverageSupportChange(
        occurrences = candidate.occurrences - occurrences,
        executions = candidate.executions - executions,
        tests = candidate.tests - tests,
        registries = candidate.registries - registries,
        batches = candidate.batches - batches,
        schemas = candidate.schemas - schemas,
    )

/** JSON encoding for the versioned resolver-coverage.json companion artifact. */
object ResolverCoverageReportJson {
    fun write(
        report: ResolverCoverageReport,
        output: Appendable,
    ) {
        output.append(PropertyTestJson.write(report))
    }

    fun read(input: Reader): ResolverCoverageReport = PropertyTestJson.read(input.readText())
}

/** Explicit local obligations can include any number of attributes; absent obligations return zero. */
class OccurrenceCoverageObligation(val id: String, val matches: (ResolverCoverageRow) -> Boolean)

/** Only complete, correctness-checked campaigns should construct a measurement. */
data class ResolverCoverageMeasurement(
    val executions: List<CoverageExecution>,
    val rows: List<ResolverCoverageRow>,
    val modelVersion: Int = RESOLVER_COVERAGE_MODEL_VERSION,
    val dependencies: List<ResolverDependencyRow> = emptyList(),
) {
    init {
        require(modelVersion == RESOLVER_COVERAGE_MODEL_VERSION) { "Incompatible coverage model" }
        require(executions.distinctBy { it.runId to it.executionId }.size == executions.size) { "Duplicate execution ID" }
        val completedExecutions = executions.toSet()
        require(rows.all { it.execution in completedExecutions }) { "Occurrence does not belong to a completed execution" }
        require(rows.distinctBy { Triple(it.runId, it.executionId, it.occurrenceId) }.size == rows.size) { "Duplicate occurrence ID" }
        require(dependencies.all { it.execution in completedExecutions }) { "Dependency does not belong to a completed execution" }
        val occurrencesById = rows.associateBy { Triple(it.runId, it.executionId, it.occurrenceId) }
        require(
            dependencies.all { dependency ->
                val provider = occurrencesById[Triple(dependency.runId, dependency.executionId, dependency.providerOccurrenceId)]
                val consumer = occurrencesById[Triple(dependency.runId, dependency.executionId, dependency.consumerOccurrenceId)]
                // Dependency observations are direct evidence. A result row may be absent for either endpoint,
                // but when it exists its coordinate must agree with the observed edge.
                (provider == null || provider.fieldCoordinate == dependency.providerFieldCoordinate) &&
                    (consumer == null || consumer.fieldCoordinate == dependency.consumerFieldCoordinate)
            }
        ) { "Dependency coordinates conflict with recorded occurrences" }
    }

    fun support(obligation: OccurrenceCoverageObligation): CoverageSupport = rows.filter { it.resolutionRole == ResolutionRole.INVOKED && obligation.matches(it) }.support()

    fun bothSourcesOnOneOccurrence(): CoverageSupport = bothSourcesOnOneOccurrenceEvidence().support()

    /** Coexistence alone establishes no provider/consumer relationship. */
    fun bothSourcesWithinOneExecution(): CoverageSupport = bothSourcesWithinOneExecutionEvidence().support()

    /** Counts executions with both a successful resolver result and a resolver error. */
    fun successfulAndFailingBranchesWithinOneExecution(): CoverageSupport = successfulAndFailingBranchesWithinOneExecutionEvidence().support()

    /** Counts terminal provider cells with recorded edges to at least two consumer occurrences. */
    fun sharedProviderFanOut(): CoverageSupport = sharedProviderFanOutEvidence().support()

    /** A terminal provider cell is identified by its occurrence path, not its field coordinate. */
    fun multipleProviderFanIn(): CoverageSupport = multipleProviderFanInEvidence().support()

    /** All three conditions belong to one invoked occurrence. */
    fun listObjectFieldError(): CoverageSupport = listObjectFieldErrorEvidence().support()

    /** A result-tree path from one invoked resolver occurrence reaches another. */
    fun resolverOutputContainsDescendant(): CoverageSupport = resolverOutputContainsDescendantEvidence().support()

    /** A provider's owning object is an ancestor of the consumer field occurrence. */
    fun descendantReadsAncestor(): CoverageSupport = descendantReadsAncestorEvidence().support()

    /** List depth comes from the resolver endpoint rows; absent endpoints do not count. */
    fun providerReadCrossesListBoundary(): CoverageSupport = providerReadCrossesListBoundaryEvidence().support()

    /** A diamond is P->A, P->B, A->C, and B->C in one execution. */
    fun providerDependencyDiamond(): CoverageSupport = providerDependencyDiamondEvidence().support()

    /** The recorded edge proves the consumer read a provider cell with a null or error outcome. */
    fun providerNullOrErrorReachesConsumer(): CoverageSupport = providerNullOrErrorReachesConsumerEvidence().support()

    internal fun bothSourcesOnOneOccurrenceEvidence() =
        rowsEvidence {
            it.resolutionRole == ResolutionRole.INVOKED && it.bindingSources.containsAll(argumentAndObjectFieldSources)
        }

    internal fun bothSourcesWithinOneExecutionEvidence(): ResolverCoverageEvidence {
        val qualifying = rows.filter { it.resolutionRole == ResolutionRole.INVOKED }
            .groupBy { it.execution }
            .filterValues { group ->
                group.any { ResolverBindingSource.ARGUMENT in it.bindingSources } &&
                    group.any { ResolverBindingSource.OBJECT_FIELD in it.bindingSources }
            }.values.flatten()
            .filter { ResolverBindingSource.ARGUMENT in it.bindingSources || ResolverBindingSource.OBJECT_FIELD in it.bindingSources }
        return ResolverCoverageEvidence(rows = qualifying.toSet())
    }

    internal fun successfulAndFailingBranchesWithinOneExecutionEvidence(): ResolverCoverageEvidence {
        val qualifying = rows.filter { it.resolutionRole == ResolutionRole.INVOKED }
            .groupBy { it.execution }
            .filterValues { group ->
                group.any { it.outputKind == OutputKind.ERROR } && group.any { it.outputKind != OutputKind.ERROR }
            }.values.flatten()
        return ResolverCoverageEvidence(rows = qualifying.toSet())
    }

    internal fun sharedProviderFanOutEvidence() =
        dependencyEvidenceGroups(
            qualifies = { edges -> edges.map { it.consumerOccurrenceId }.distinct().size > 1 },
            key = { Triple(it.runId, it.executionId, it.providerOccurrenceId) },
        )

    internal fun multipleProviderFanInEvidence() =
        dependencyEvidenceGroups(
            qualifies = { edges -> edges.map { it.providerOccurrenceId }.distinct().size > 1 },
            key = { Triple(it.runId, it.executionId, it.consumerOccurrenceId) },
        )

    internal fun listObjectFieldErrorEvidence() =
        rowsEvidence {
            it.resolutionRole == ResolutionRole.INVOKED && it.locus == ResolverLocus.LIST_ELEMENT_OBJECT &&
                ResolverBindingSource.OBJECT_FIELD in it.bindingSources && it.outputKind == OutputKind.ERROR
        }

    internal fun resolverOutputContainsDescendantEvidence(): ResolverCoverageEvidence = ResolverCoverageEvidence(rows = resultTreeDescendantPairs().flatMap { listOf(it.first, it.second) }.toSet())

    internal fun descendantReadsAncestorEvidence(): ResolverCoverageEvidence = dependencyEvidence(descendantReadEdges())

    internal fun providerReadCrossesListBoundaryEvidence(): ResolverCoverageEvidence = dependencyEvidence(listBoundaryReadEdges())

    internal fun providerDependencyDiamondEvidence(): ResolverCoverageEvidence = ResolverCoverageEvidence(dependencyGroups = dependencies.diamondGroups())

    internal fun providerNullOrErrorReachesConsumerEvidence(): ResolverCoverageEvidence = dependencyEvidence(providerOutcomeEdges())

    private fun rowsEvidence(matches: (ResolverCoverageRow) -> Boolean) = ResolverCoverageEvidence(rows = rows.filter(matches).toSet())

    private fun dependencyEvidence(edges: List<ResolverDependencyRow>) = ResolverCoverageEvidence(dependencyGroups = edges.map(::listOf))

    private fun dependencyEvidenceGroups(
        qualifies: (List<ResolverDependencyRow>) -> Boolean,
        key: (ResolverDependencyRow) -> Any,
    ) = ResolverCoverageEvidence(
        dependencyGroups = dependencies.groupBy(key).values.filter(qualifies),
    )

    internal fun resultTreeDescendantPairs(): List<Pair<ResolverCoverageRow, ResolverCoverageRow>> {
        val byExecution = rows.filter { it.resolutionRole == ResolutionRole.INVOKED }.groupBy { it.runId to it.executionId }
        return buildList {
            byExecution.values.forEach { executionRows ->
                val paths = executionRows.associateWith(ResolverCoverageRow::parsedResultPath)
                executionRows.forEach { ancestor ->
                    executionRows.forEach { descendant ->
                        if (ancestor != descendant && paths.getValue(ancestor).isStrictAncestorOf(paths.getValue(descendant))) {
                            add(ancestor to descendant)
                        }
                    }
                }
            }
        }
    }

    internal fun descendantReadEdges(): List<ResolverDependencyRow> =
        dependencies.filter { edge ->
            val providerOwner = edge.provider.occurrenceCoordinate?.let(::parsedResolverResultPath)?.ownerPath()
            val consumerPath = edge.consumer.occurrenceCoordinate?.let(::parsedResolverResultPath)
            providerOwner != null && consumerPath != null && providerOwner.isStrictAncestorOf(consumerPath)
        }

    internal fun listBoundaryReadEdges(): List<ResolverDependencyRow> {
        val rowsByOccurrence = rows.associateBy { Triple(it.runId, it.executionId, it.occurrenceId) }
        return dependencies.filter { edge ->
            val provider = rowsByOccurrence[Triple(edge.runId, edge.executionId, edge.providerOccurrenceId)]
            val consumer = rowsByOccurrence[Triple(edge.runId, edge.executionId, edge.consumerOccurrenceId)]
            provider != null && consumer != null && provider.listAncestorCount != consumer.listAncestorCount
        }
    }

    internal fun providerOutcomeEdges(): List<ResolverDependencyRow> = dependencies.filter { it.providerOutcome != ProviderCellOutcome.VALUE }

    /** Reports each RFC obligation separately, including obligations with zero support. */
    fun report(): ResolverCoverageReport =
        ResolverCoverageReport(
            modelVersion = modelVersion,
            executions = executions,
            metrics = resolverCoverageMetricCatalog.map { it.measure(this) },
            attributes = resolverCoverageAttributes(this),
            analysis = analyze(),
        )
}

/** Combines completed suite measurements while preserving each saved case's suite-scoped identity. */
fun combineCoverageMeasurements(measurements: Collection<ResolverCoverageMeasurement>): ResolverCoverageMeasurement {
    require(measurements.isNotEmpty()) { "At least one suite measurement is required" }
    require(measurements.map { it.modelVersion }.distinct().size == 1) { "Cannot combine different coverage model versions" }
    val executions =
        measurements.flatMap { measurement ->
            measurement.executions.map { execution -> execution.copy(test = execution.test.withSuiteScopedSchemaId()) }
        }
    val executionsById = executions.associateBy { it.runId to it.executionId }
    return ResolverCoverageMeasurement(
        executions = executions,
        rows =
            measurements.flatMap { measurement ->
                measurement.rows.map { row -> row.copy(schemaId = row.test.withSuiteScopedSchemaId().schemaId) }
            },
        modelVersion = measurements.first().modelVersion,
        dependencies =
            measurements.flatMap { measurement ->
                measurement.dependencies.map { dependency ->
                    dependency.copy(execution = executionsById.getValue(dependency.runId to dependency.executionId))
                }
            },
    )
}

private fun CoverageTestId.withSuiteScopedSchemaId(): CoverageTestId {
    val suitePrefix = "${batchId.substringBefore('/')}/"
    return if (schemaId.startsWith(suitePrefix)) this else copy(schemaId = "$suitePrefix$schemaId")
}

internal fun resolverCoverageAttributes(measurement: ResolverCoverageMeasurement): List<ResolverCoverageAttribute> {
    val exactListDepths = measurement.rows.filter { it.resolutionRole == ResolutionRole.INVOKED }.map { it.listAncestorCount }.distinct().sorted()
    val exactProviderPathDepths =
        measurement.rows.filter { it.resolutionRole == ResolutionRole.INVOKED }.mapNotNull { it.maxProviderPathDepth }.distinct().sorted()
    val openEndedAttributes =
        buildList {
            exactListDepths.forEach { depth ->
                add(resolverAttribute("list_ancestor_count:$depth", "An invoked resolver has exactly $depth list ancestors.") { it.listAncestorCount == depth })
            }
            exactProviderPathDepths.forEach { depth ->
                add(resolverAttribute("provider_path_depth:$depth", "An invoked resolver has a provider path of exactly $depth steps.") { it.maxProviderPathDepth == depth })
            }
        }
    return (resolverCoverageAttributeDefinitions + openEndedAttributes).map { it.measure(measurement) }
}

private fun bindingSourcesLabel(sources: Set<ResolverBindingSource>): String = if (sources.isEmpty()) "none" else sources.map { it.name.lowercase() }.sorted().joinToString("+")

internal data class ParsedResolverResultPath(val root: String, val components: List<Any?>) {
    fun isStrictAncestorOf(descendant: ParsedResolverResultPath): Boolean =
        root == descendant.root && components.size < descendant.components.size &&
            components == descendant.components.take(components.size)

    fun ownerPath(): ParsedResolverResultPath = copy(components = components.dropLast(1))
}

internal fun parsedResolverResultPath(resultPath: String): ParsedResolverResultPath {
    val path = PropertyTestJson.read<Map<String, Any?>>(resultPath)
    val root = requireNotNull(path["root"] as? String) { "Coverage result path has no root" }
    val components = requireNotNull(path["path"] as? List<*>) { "Coverage result path has no path components" }
    return ParsedResolverResultPath(root, components)
}

internal fun ResolverCoverageRow.parsedResultPath(): ParsedResolverResultPath = parsedResolverResultPath(resultPath)

internal fun Collection<ResolverDependencyRow>.diamondGroups(): List<List<ResolverDependencyRow>> {
    val diamonds = mutableListOf<List<ResolverDependencyRow>>()
    groupBy { it.runId to it.executionId }.values.forEach { executionEdges ->
        val byEndpoints = executionEdges.associateBy { it.providerOccurrenceId to it.consumerOccurrenceId }
        val consumersByProvider = executionEdges.groupBy { it.providerOccurrenceId }
            .mapValues { (_, edges) -> edges.mapTo(linkedSetOf()) { it.consumerOccurrenceId } }
        consumersByProvider.forEach { (source, firstHops) ->
            val mids = firstHops.toList()
            for (leftIndex in mids.indices) {
                for (rightIndex in leftIndex + 1 until mids.size) {
                    val left = mids[leftIndex]
                    val right = mids[rightIndex]
                    val commonTargets = consumersByProvider[left].orEmpty().intersect(consumersByProvider[right].orEmpty())
                    commonTargets.filter { it != source && it != left && it != right }.forEach { target ->
                        val edges = listOfNotNull(
                            byEndpoints[source to left],
                            byEndpoints[source to right],
                            byEndpoints[left to target],
                            byEndpoints[right to target],
                        )
                        if (edges.size == 4) diamonds += edges
                    }
                }
            }
        }
    }
    return diamonds
}

private val argumentAndObjectFieldSources =
    setOf(ResolverBindingSource.ARGUMENT, ResolverBindingSource.OBJECT_FIELD)

private fun List<ResolverCoverageRow>.support(): CoverageSupport =
    CoverageSupport(
        occurrences = map { Triple(it.runId, it.executionId, it.occurrenceId) }.distinct().size,
        executions = map { it.runId to it.executionId }.distinct().size,
        tests = map { it.test }.distinct().size,
        registries = map { it.batchId to it.registryId }.distinct().size,
        batches = map { it.batchId }.distinct().size,
        schemas = map { it.schemaId }.distinct().size,
    )

private fun Collection<List<ResolverDependencyRow>>.dependencySupport(): CoverageSupport {
    val evidence = map { it.first() }
    return CoverageSupport(
        occurrences = size,
        executions = evidence.map { it.runId to it.executionId }.distinct().size,
        tests = evidence.map { it.execution.test }.distinct().size,
        registries = evidence.map { it.batchId to it.registryId }.distinct().size,
        batches = evidence.map { it.batchId }.distinct().size,
        schemas = evidence.map { it.schemaId }.distinct().size,
    )
}
