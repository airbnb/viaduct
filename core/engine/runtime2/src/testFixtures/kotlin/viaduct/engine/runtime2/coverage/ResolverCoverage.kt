package viaduct.engine.runtime2.coverage

import viaduct.engine.runtime2.propertytest.ResolverTestCaseId

/** Version of the RFC-271 column meanings, independent of saved-suite storage versions. */
const val RESOLVER_COVERAGE_MODEL_VERSION = 3

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

data class CoverageSupport(val occurrences: Int, val executions: Int, val tests: Int, val registries: Int, val schemas: Int)

data class ExecutionCoverageSupport(val executions: Int, val tests: Int, val registries: Int, val schemas: Int)

/** Explicit local obligations can include any number of attributes; absent obligations return zero. */
class OccurrenceCoverageObligation(val id: String, val matches: (ResolverCoverageRow) -> Boolean)

/** Only complete, correctness-checked campaigns should construct a measurement. */
data class ResolverCoverageMeasurement(
    val executions: List<CoverageExecution>,
    val rows: List<ResolverCoverageRow>,
    val modelVersion: Int = RESOLVER_COVERAGE_MODEL_VERSION,
) {
    init {
        require(modelVersion == RESOLVER_COVERAGE_MODEL_VERSION) { "Incompatible coverage model" }
        require(executions.distinctBy { it.runId to it.executionId }.size == executions.size) { "Duplicate execution ID" }
        require(rows.all { it.execution in executions }) { "Occurrence does not belong to a completed execution" }
        require(rows.distinctBy { Triple(it.runId, it.executionId, it.occurrenceId) }.size == rows.size) { "Duplicate occurrence ID" }
    }

    fun support(obligation: OccurrenceCoverageObligation): CoverageSupport = rows.filter { it.resolutionRole == ResolutionRole.INVOKED && obligation.matches(it) }.support()

    fun bothSourcesOnOneOccurrence(): CoverageSupport =
        support(
            OccurrenceCoverageObligation("both-sources-on-one-occurrence") {
                it.bindingSources.containsAll(argumentAndObjectFieldSources)
            }
        )

    /** Coexistence alone establishes no provider/consumer relationship. */
    fun bothSourcesWithinOneExecution(): ExecutionCoverageSupport {
        val qualifying = rows.filter { it.resolutionRole == ResolutionRole.INVOKED }
            .groupBy { it.execution }
            .filterValues { group ->
                group.any { ResolverBindingSource.ARGUMENT in it.bindingSources } &&
                    group.any { ResolverBindingSource.OBJECT_FIELD in it.bindingSources }
            }.values.flatten()
        val support = qualifying.support()
        return ExecutionCoverageSupport(support.executions, support.tests, support.registries, support.schemas)
    }
}

private val argumentAndObjectFieldSources =
    setOf(ResolverBindingSource.ARGUMENT, ResolverBindingSource.OBJECT_FIELD)

private fun List<ResolverCoverageRow>.support(): CoverageSupport =
    CoverageSupport(
        occurrences = size,
        executions = map { it.runId to it.executionId }.distinct().size,
        tests = map { it.test }.distinct().size,
        registries = map { it.batchId to it.registryId }.distinct().size,
        schemas = map { it.schemaId }.distinct().size,
    )
