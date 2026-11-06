package viaduct.engine.runtime2.coverage

import java.util.IdentityHashMap
import viaduct.apiannotations.VisibleForTest
import viaduct.engine.runtime2.arbitrary.ResolutionWitnessBoundExceededException
import viaduct.engine.runtime2.arbitrary.ResolutionWitnessBounds
import viaduct.engine.runtime2.arbitrary.resolutionFingerprint
import viaduct.engine.runtime2.contract.ResolverResolutionObservation
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.sourceSuppliedResolverOccurrences
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.fieldExpressions
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.render
import viaduct.engine.runtime2.model.usedVariables
import viaduct.engine.runtime2.propertytest.PropertyTestJson
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.groundedArguments
import viaduct.graphql.schema.ViaductSchema

/** Extract after correctness checks. Non-invocation alone is never evidence of source supply. */
suspend fun ResolverResolutionObservation.coverageRows(
    execution: CoverageExecution,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): List<ResolverCoverageRow> = ResolverCoverageExtractor(this, execution, bounds).extract()

private class ResolverCoverageExtractor(
    private val observation: ResolverResolutionObservation,
    private val execution: CoverageExecution,
    private val bounds: ResolutionWitnessBounds,
) {
    private val operation = observation.operation

    suspend fun extract(): List<ResolverCoverageRow> {
        val roots = collectRoots()
        val supplied = roots.flatMapTo(mutableSetOf()) { it.result.sourceSuppliedResolverOccurrences(operation) }
        val fields = collectFields(roots)
        val paths = CoveragePathEncoder(operation, fields)
        return fields.map { field -> row(field, supplied, paths) }
    }

    private fun collectRoots(): List<CoverageRoot> {
        val roots = mutableListOf<CoverageRoot>()
        val byResult = IdentityHashMap<ObjectEngineResult, CoverageRoot>()

        fun addRoot(
            root: ObjectEngineResult,
            label: String
        ) {
            val existing = byResult[root]
            if (existing != null) {
                existing.labels += label
                return
            }
            val coverageRoot = CoverageRoot(root, mutableSetOf(label))
            roots += coverageRoot
            byResult[root] = coverageRoot
        }

        addRoot(observation.result, "result")
        val observer = operation.resolutionObserver as? CorrectnessResolverObserver
        observer
            ?.allQueryFragmentResults()
            ?.values
            ?.flatten()
            ?.forEach { addRoot(it, "queryFragment-${roots.size}") }
        return roots
    }

    private suspend fun collectFields(roots: List<CoverageRoot>): List<CoverageField> {
        val fields = mutableListOf<CoverageField>()
        var visited = 0

        suspend fun visit(
            root: CoverageRoot,
            value: EngineResult?,
            path: List<PathComponent>,
        ) {
            if (++visited > bounds.maxResultNodes) {
                throw ResolutionWitnessBoundExceededException("coverage-result-node", bounds.maxResultNodes)
            }
            when (value) {
                is ObjectEngineResult -> value.keys.sortedWith(
                    compareBy(
                        { it.field.name },
                        { it.arguments.resolutionFingerprint(it.field).value },
                    )
                ).forEach { key ->
                    val cell = value.getCell(key)
                    if (!cell.fetchActivated()) return@forEach
                    check(cell.value.isCompleted) { "Coverage requires a completed execution" }
                    val fieldPath = path + key
                    if (key.field in operation.world.resolverRegistry) {
                        fields += CoverageField(root, key, fieldPath, cell.value.get())
                    }
                    if (key !is ObjectEngineResult.ParentKey) visit(root, cell.value.get(), fieldPath)
                }
                is ListEngineResult -> value.forEachIndexed { index, cell ->
                    if (cell.fetchActivated()) {
                        check(cell.value.isCompleted) { "Coverage requires a completed execution" }
                        visit(root, cell.value.get(), path + ListEngineResult.Index.of(index))
                    }
                }
            }
        }

        roots.forEach { root -> visit(root, root.result, emptyList()) }
        return fields
    }

    private fun row(
        occurrence: CoverageField,
        supplied: Set<ResolverOccurrenceId>,
        paths: CoveragePathEncoder,
    ): ResolverCoverageRow {
        val key = occurrence.key
        val occurrenceId = ResolverOccurrenceId.at(occurrence.root.result, occurrence.path)
        val arguments = key.groundedArguments(operation)
        val sources = operation.world.resolverRegistry.resolver(key.field).variables.values
        val ownerPath = occurrence.path.dropLast(1)
        val role = when {
            occurrenceId in observation.appliedResolverOccurrences.orEmpty() -> ResolutionRole.INVOKED
            arguments == Arguments.Error -> ResolutionRole.ARGUMENT_ERROR_NOT_INVOKED
            occurrenceId in supplied -> ResolutionRole.SOURCE_SUPPLIED
            else -> ResolutionRole.UNKNOWN
        }

        return ResolverCoverageRow(
            runId = execution.runId,
            executionId = execution.executionId,
            batchId = execution.test.batchId,
            schemaId = execution.test.schemaId,
            registryId = execution.test.registryId,
            queryId = execution.test.queryId,
            occurrenceId = paths.id(occurrenceId),
            resultPath = paths.path(occurrence.root, occurrence.path),
            fieldCoordinate = "${key.field.containingDef.name}.${key.field.name}",
            resolutionRole = role,
            locus = when {
                ownerPath.isEmpty() -> ResolverLocus.QUERY_ROOT
                ownerPath.last() is ListEngineResult.Index -> ResolverLocus.LIST_ELEMENT_OBJECT
                else -> ResolverLocus.OTHER_OBJECT
            },
            stampClass = if (key.arguments.usedVariables().isEmpty()) StampClass.VARIABLE_FREE else StampClass.OCCURRENCE_STAMPED,
            listAncestorCount = ownerPath.count { it is ListEngineResult.Index },
            bindingSources = sources.resolverBindingSources(),
            maxProviderPathDepth = sources.filterIsInstance<VariableDefinition.FromField>().maxProviderPathDepth(),
            argumentState = when {
                arguments == Arguments.Error -> ArgumentState.ERROR
                arguments is Arguments.Resolved && arguments.fieldValues.isEmpty() -> ArgumentState.NONE
                arguments is Arguments.Resolved && arguments.fieldValues.values.any(::containsNull) -> ArgumentState.CONTAINS_NULL
                else -> ArgumentState.RESOLVED
            },
            declaredOutputShape = when {
                key.field.type.isList -> DeclaredOutputShape.LIST
                key.field.type.baseTypeDef is ViaductSchema.CompositeTypeDef -> DeclaredOutputShape.OBJECT
                else -> DeclaredOutputShape.SCALAR
            },
            outputKind = occurrence.value.outputKind(),
        )
    }
}

private class CoveragePathEncoder(
    private val operation: SharedOperationContext<*>,
    occurrences: List<CoverageField>,
) {
    private val ids = linkedMapOf<ResolverOccurrenceId, String>()

    init {
        occurrences.forEach { id(ResolverOccurrenceId.at(it.root.result, it.path)) }
    }

    fun id(occurrence: ResolverOccurrenceId): String = ids.getOrPut(occurrence) { "occurrence-${ids.size + 1}" }

    fun path(
        root: CoverageRoot,
        path: List<PathComponent>,
    ): String =
        PropertyTestJson.write(
            mapOf(
                "root" to root.label,
                "path" to path.map { component ->
                    when (component) {
                        is ListEngineResult.Index -> mapOf("index" to component.index)
                        is ObjectEngineResult.ObjectKey -> mapOf(
                            "field" to "${component.field.containingDef.name}.${component.field.name}",
                            "arguments" to component.groundedArguments(operation).resolutionFingerprint(component.field).value,
                            "expressions" to if (component.arguments == Arguments.Error) "ERROR" else expression(component.arguments.fieldExpressions()),
                            "responseKey" to (component as? ObjectEngineResult.MutationKey)?.responseKey,
                        )
                    }
                },
            )
        ).trimEnd()

    private fun expression(value: Any?): Any? =
        when (value) {
            is Arguments.Variable -> mapOf(
                "variable" to value.variableName,
                "target" to value.target.render(),
                "owner" to id(requireNotNull(value.instanceId).resolverOccurrenceId),
            )
            is Map<*, *> -> value.entries.associate { it.key.toString() to expression(it.value) }
            is List<*> -> value.map(::expression)
            null -> null
            else -> value.toString()
        }
}

private data class CoverageRoot(
    val result: ObjectEngineResult,
    val labels: MutableSet<String>,
) {
    val label: String get() = labels.sorted().joinToString("+")
}

private data class CoverageField(
    val root: CoverageRoot,
    val key: ObjectEngineResult.ObjectKey,
    val path: List<PathComponent>,
    val value: EngineResult?,
)

private fun EngineResult?.outputKind(): OutputKind =
    when (this) {
        null -> OutputKind.NULL
        is ErrorEngineResult -> OutputKind.ERROR
        is ObjectEngineResult -> OutputKind.OBJECT
        is ListEngineResult -> OutputKind.LIST
        else -> OutputKind.SCALAR
    }

private fun containsNull(value: Any?): Boolean =
    when (value) {
        null -> true
        is Map<*, *> -> value.values.any(::containsNull)
        is List<*> -> value.any(::containsNull)
        else -> false
    }

@VisibleForTest
fun Iterable<VariableDefinition>.resolverBindingSources(): Set<ResolverBindingSource> =
    mapTo(linkedSetOf()) { source ->
        when (source) {
            VariableDefinition.FromProvider -> ResolverBindingSource.PROVIDER
            is VariableDefinition.FromArgument -> ResolverBindingSource.ARGUMENT
            is VariableDefinition.FromField -> when (source.providerFragment) {
                ProviderFragment.OBJECT -> ResolverBindingSource.OBJECT_FIELD
                ProviderFragment.QUERY -> ResolverBindingSource.QUERY_FIELD
            }
        }
    }

@VisibleForTest
fun Iterable<VariableDefinition.FromField>.maxProviderPathDepth(): Int? = maxOfOrNull { it.path.size }
