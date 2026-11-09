package viaduct.engine.runtime2.coverage

import viaduct.engine.runtime2.propertytest.ResolverTestCaseId
import viaduct.engine.runtime2.resolution.framework.ProviderCellOutcome

/** Version of the RFC-271 column meanings, independent of saved-suite storage versions. */
const val RESOLVER_COVERAGE_MODEL_VERSION = 4

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
) {
    init {
        require(listOf(occurrenceId, fieldCoordinate).none(String::isBlank))
    }
}

data class ResolverCoverageRecords(
    val occurrences: List<ResolverCoverageRow>,
    val dependencies: List<ResolverDependencyRow>,
)

data class CoverageSupport(
    val occurrences: Int,
    val executions: Int,
    val tests: Int,
    val registries: Int,
    val batches: Int,
    val schemas: Int,
)

/** Counts supporting occurrences and the saved-suite levels in which they appear. */
data class ResolverCoverageScores(
    val bothSourcesOnOneOccurrence: CoverageSupport,
    val bothSourcesWithinOneExecution: CoverageSupport,
    val successfulAndFailingBranchesWithinOneExecution: CoverageSupport,
    val sharedProviderFanOut: CoverageSupport,
    val multipleProviderFanIn: CoverageSupport,
)

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
                (provider == null || provider.fieldCoordinate == dependency.providerFieldCoordinate) &&
                    consumer?.fieldCoordinate == dependency.consumerFieldCoordinate
            }
        ) { "Dependency consumer does not match a recorded occurrence, or provider coordinate conflicts with one" }
    }

    fun support(obligation: OccurrenceCoverageObligation): CoverageSupport = rows.filter { it.resolutionRole == ResolutionRole.INVOKED && obligation.matches(it) }.support()

    fun bothSourcesOnOneOccurrence(): CoverageSupport =
        support(
            OccurrenceCoverageObligation("both-sources-on-one-occurrence") {
                it.bindingSources.containsAll(argumentAndObjectFieldSources)
            }
        )

    /** Coexistence alone establishes no provider/consumer relationship. */
    fun bothSourcesWithinOneExecution(): CoverageSupport {
        val qualifying = rows.filter { it.resolutionRole == ResolutionRole.INVOKED }
            .groupBy { it.execution }
            .filterValues { group ->
                group.any { ResolverBindingSource.ARGUMENT in it.bindingSources } &&
                    group.any { ResolverBindingSource.OBJECT_FIELD in it.bindingSources }
            }.values.flatten()
            .filter { ResolverBindingSource.ARGUMENT in it.bindingSources || ResolverBindingSource.OBJECT_FIELD in it.bindingSources }
        return qualifying.support()
    }

    /** Counts executions with both a successful resolver result and a resolver error. */
    fun successfulAndFailingBranchesWithinOneExecution(): CoverageSupport =
        rows.filter { it.resolutionRole == ResolutionRole.INVOKED }
            .groupBy { it.execution }
            .filterValues { group ->
                group.any { it.outputKind == OutputKind.ERROR } &&
                    group.any { it.outputKind != OutputKind.ERROR }
            }.values.flatten()
            .support()

    /** Counts terminal provider cells with recorded edges to at least two consumer occurrences. */
    fun sharedProviderFanOut(): CoverageSupport =
        dependencies
            .groupBy { Triple(it.runId, it.executionId, it.providerOccurrenceId) }
            .filterValues { edges -> edges.map { it.consumerOccurrenceId }.distinct().size > 1 }
            .values
            .dependencySupport()

    /**
     * Counts consumers that read at least two distinct terminal provider cells.
     * A cell is identified by its occurrence path, so two cells supplied by the same upstream
     * resolver still count separately. This metric does not infer or group by upstream resolver.
     */
    fun multipleProviderFanIn(): CoverageSupport =
        dependencies
            .groupBy { Triple(it.runId, it.executionId, it.consumerOccurrenceId) }
            .filterValues { edges -> edges.map { it.providerOccurrenceId }.distinct().size > 1 }
            .values
            .dependencySupport()

    fun scores(): ResolverCoverageScores =
        ResolverCoverageScores(
            bothSourcesOnOneOccurrence = bothSourcesOnOneOccurrence(),
            bothSourcesWithinOneExecution = bothSourcesWithinOneExecution(),
            successfulAndFailingBranchesWithinOneExecution = successfulAndFailingBranchesWithinOneExecution(),
            sharedProviderFanOut = sharedProviderFanOut(),
            multipleProviderFanIn = multipleProviderFanIn(),
        )
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
