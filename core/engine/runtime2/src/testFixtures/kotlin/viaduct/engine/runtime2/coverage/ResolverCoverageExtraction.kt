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
import viaduct.engine.runtime2.resolution.framework.ResolverProviderReadObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.groundedArguments
import viaduct.graphql.schema.ViaductSchema

/** Extract after correctness checks. Non-invocation alone is never evidence of source supply. */
suspend fun ResolverResolutionObservation.coverageRows(
    execution: CoverageExecution,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): List<ResolverCoverageRow> = coverageRecords(execution, bounds).occurrences

suspend fun ResolverResolutionObservation.coverageRecords(
    execution: CoverageExecution,
    bounds: ResolutionWitnessBounds = ResolutionWitnessBounds(),
): ResolverCoverageRecords = ResolverCoverageExtractor(this, execution, bounds).extract()

private class ResolverCoverageExtractor(
    private val observation: ResolverResolutionObservation,
    private val execution: CoverageExecution,
    private val bounds: ResolutionWitnessBounds,
) {
    private val operation = observation.operation

    suspend fun extract(): ResolverCoverageRecords {
        val discoveredRoots = collectRoots()
        val collectedFields = collectFields(discoveredRoots)
        val roots = orderRoots(discoveredRoots)
        val rootOrder = IdentityHashMap<CoverageRoot, Int>().apply {
            roots.forEachIndexed { index, root -> put(root, index) }
        }
        val fields = collectedFields.sortedBy { rootOrder.getValue(it.root) }
        val rootsByResult = IdentityHashMap<ObjectEngineResult, CoverageRoot>().apply {
            roots.forEach { put(it.result, it) }
        }
        val paths = CoveragePathEncoder(operation, fields)
        val supplied =
            roots.filter(CoverageRoot::hasValidatedSourceProvenance)
                .flatMapTo(mutableSetOf()) { it.result.sourceSuppliedResolverOccurrences(operation) }
        // Query-fragment roots are partial inputs checked in their owner resolver's context, so
        // they cannot be checked as complete standalone results. A registered field that was
        // activated there, had valid arguments, and has no recorded invocation was supplied by
        // the source object in that validated fragment.
        val appliedOccurrences = observation.appliedResolverOccurrences
        if (appliedOccurrences != null) {
            fields.filter { field ->
                CoverageRootKind.QUERY_FRAGMENT in field.root.kinds &&
                    field.key.field in operation.world.resolverRegistry &&
                    field.key.groundedArguments(operation) != Arguments.Error &&
                    ResolverOccurrenceId.at(field.root.result, field.path) !in appliedOccurrences
            }.mapTo(supplied) { field -> ResolverOccurrenceId.at(field.root.result, field.path) }
        }
        val occurrences = fields.map { field -> occurrenceRow(field, supplied, paths) }
        val dependencies = dependencyRows(rootsByResult, paths)
        return ResolverCoverageRecords(occurrences, dependencies)
    }

    /** Prefer the more specific query-fragment label when one result appears in both collections. */
    private fun collectRoots(): List<CoverageRoot> {
        val roots = mutableListOf<CoverageRoot>()
        val byResult = IdentityHashMap<ObjectEngineResult, CoverageRoot>()

        fun addRoot(
            result: ObjectEngineResult,
            kind: CoverageRootKind,
            stableKey: String = "",
        ) {
            val role = CoverageRootRole(kind, stableKey)
            val existing = byResult[result]
            if (existing != null) {
                existing.addRole(role)
                return
            }
            val root = CoverageRoot(result).apply { addRole(role) }
            roots += root
            byResult[result] = root
        }

        addRoot(observation.result, CoverageRootKind.OPERATION_RESULT)
        val queryObserver =
            operation.resolutionObserver as? CorrectnessResolverObserver
                ?: error("RFC-271 coverage requires a CorrectnessResolverObserver to record query-fragment roots")
        queryObserver.allQueryOERs().keys.forEach { result -> addRoot(result, CoverageRootKind.QUERY_OER) }
        queryObserver.allQueryFragmentResults().values.flatten().forEach { result ->
            addRoot(result, CoverageRootKind.QUERY_FRAGMENT)
        }
        observation.providerReadObservations.forEach { read ->
            addRoot(read.provider.root, CoverageRootKind.PROVIDER, stableOccurrenceKey(read.provider.path, read.provider.field))
            addRoot(read.consumer.root, CoverageRootKind.CONSUMER, stableOccurrenceKey(read.consumer.path, read.consumer.field))
        }
        return roots
    }

    private fun orderRoots(roots: List<CoverageRoot>): List<CoverageRoot> {
        val orderedRoots = roots.sortedWith(compareBy(CoverageRoot::stableSortPriority, CoverageRoot::stableSortKey, CoverageRoot::stableSignature))
        val nextIndexByKind = mutableMapOf<CoverageRootKind, Int>()
        orderedRoots.forEach { root ->
            root.kinds.sortedBy(CoverageRootKind::priority).forEach { kind ->
                if (kind != CoverageRootKind.OPERATION_RESULT) {
                    val nextIndex = (nextIndexByKind[kind] ?: 0) + 1
                    root.setIndex(kind, nextIndex)
                    nextIndexByKind[kind] = nextIndex
                }
            }
        }
        return orderedRoots
    }

    private fun stableOccurrenceKey(
        path: List<PathComponent>,
        field: ViaductSchema.ObjectField,
    ): String =
        PropertyTestJson.write(
            mapOf(
                "path" to path.map(::stablePathComponent),
                "field" to "${field.containingDef.name}.${field.name}",
            ),
        ).trimEnd()

    private fun stableProviderReadKey(
        read: ResolverProviderReadObservation,
        rootsByResult: IdentityHashMap<ObjectEngineResult, CoverageRoot>,
    ): String =
        PropertyTestJson.write(
            listOf(
                mapOf(
                    "root" to rootsByResult.getValue(read.provider.root).stableSignature,
                    "occurrence" to stableOccurrenceKey(read.provider.path, read.provider.field),
                ),
                mapOf(
                    "root" to rootsByResult.getValue(read.consumer.root).stableSignature,
                    "occurrence" to stableOccurrenceKey(read.consumer.path, read.consumer.field),
                ),
                read.variableName,
                read.providerFragment.name,
                read.outcome.name,
            ),
        ).trimEnd()

    private fun providerReadsInStableOrder(rootsByResult: IdentityHashMap<ObjectEngineResult, CoverageRoot>): List<ResolverProviderReadObservation> =
        observation.providerReadObservations
            .map { it to stableProviderReadKey(it, rootsByResult) }
            .sortedBy { it.second }
            .map { it.first }

    private fun stablePathComponent(component: PathComponent): Map<String, Any?> =
        when (component) {
            is ListEngineResult.Index -> mapOf("index" to component.index)
            is ObjectEngineResult.ObjectKey -> mapOf(
                "field" to "${component.field.containingDef.name}.${component.field.name}",
                "arguments" to component.arguments.resolutionFingerprint(component.field).value,
                "responseKey" to (component as? ObjectEngineResult.MutationKey)?.responseKey,
            )
        }

    private fun stableCellKey(
        path: List<PathComponent>,
        outputKind: String,
    ): String =
        PropertyTestJson.write(
            mapOf("path" to path.map(::stablePathComponent), "output" to outputKind),
        ).trimEnd()

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
                        { (it as? ObjectEngineResult.MutationKey)?.responseKey.orEmpty() },
                    )
                ).forEach { key ->
                    val fieldPath = path + key
                    val cell = value.getCell(key)
                    if (!cell.fetchActivated()) {
                        root.addSortSegment(stableCellKey(fieldPath, "inactive"))
                        return@forEach
                    }
                    check(cell.value.isCompleted) { "Coverage requires a completed execution" }
                    val child = cell.value.get()
                    root.addSortSegment(stableCellKey(fieldPath, child.outputKind().name))
                    if (key.field in operation.world.resolverRegistry) {
                        fields += CoverageField(root, key, fieldPath, child)
                    }
                    if (key !is ObjectEngineResult.ParentKey) visit(root, child, fieldPath)
                }
                is ListEngineResult -> value.forEachIndexed { index, cell ->
                    val elementPath = path + ListEngineResult.Index.of(index)
                    if (cell.fetchActivated()) {
                        check(cell.value.isCompleted) { "Coverage requires a completed execution" }
                        val child = cell.value.get()
                        root.addSortSegment(stableCellKey(elementPath, child.outputKind().name))
                        visit(root, child, elementPath)
                    } else {
                        root.addSortSegment(stableCellKey(elementPath, "inactive"))
                    }
                }
            }
        }

        roots.forEach { visit(it, it.result, emptyList()) }
        return fields
    }

    private fun occurrenceRow(
        occurrence: CoverageField,
        supplied: Set<ResolverOccurrenceId>,
        paths: CoveragePathEncoder,
    ): ResolverCoverageRow {
        val key = occurrence.key
        val occurrenceId = ResolverOccurrenceId.at(occurrence.root.result, occurrence.path)
        val arguments = key.groundedArguments(operation)
        val sources = operation.world.resolverRegistry.resolver(key.field).declaredDependencies.variables.values
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
            argumentState = arguments.argumentState(),
            declaredOutputShape = key.field.declaredOutputShape(),
            outputKind = occurrence.value.outputKind(),
        )
    }

    private suspend fun dependencyRows(
        rootsByResult: IdentityHashMap<ObjectEngineResult, CoverageRoot>,
        paths: CoveragePathEncoder,
    ): List<ResolverDependencyRow> {
        fun rootFor(result: ObjectEngineResult): CoverageRoot = requireNotNull(rootsByResult[result]) { "Provider read used a result root that was not recorded" }

        return providerReadsInStableOrder(rootsByResult)
            .map { read ->
                val provider = read.provider
                val consumer = read.consumer
                val providerRoot = rootFor(provider.root)
                val consumerRoot = rootFor(consumer.root)
                ResolverDependencyRow(
                    execution = execution,
                    provider = ResolverDependencyOccurrence(
                        occurrenceId = paths.id(ResolverOccurrenceId.at(providerRoot.result, provider.path)),
                        fieldCoordinate = "${provider.field.containingDef.name}.${provider.field.name}",
                        occurrenceCoordinate = paths.path(providerRoot, provider.path),
                    ),
                    providerSource = read.providerFragment.bindingSource(),
                    providerOutcome = read.outcome,
                    consumer = ResolverDependencyOccurrence(
                        occurrenceId = paths.id(ResolverOccurrenceId.at(consumerRoot.result, consumer.path)),
                        fieldCoordinate = "${consumer.field.containingDef.name}.${consumer.field.name}",
                        occurrenceCoordinate = paths.path(consumerRoot, consumer.path),
                    ),
                    variableName = read.variableName,
                )
            }
    }
}

private class CoveragePathEncoder(
    private val operation: SharedOperationContext<*>,
    occurrences: List<CoverageField>,
) {
    private val ids = linkedMapOf<ResolverOccurrenceId, String>()
    private val coordinates = linkedMapOf<ResolverOccurrenceId, String>()
    private val occurrencesById = occurrences.associateBy { ResolverOccurrenceId.at(it.root.result, it.path) }
    private val coordinatesInProgress = mutableSetOf<ResolverOccurrenceId>()

    init {
        occurrencesById.keys.forEach(::id)
        occurrencesById.keys.forEach(::coordinateFor)
    }

    fun id(occurrence: ResolverOccurrenceId): String = ids.getOrPut(occurrence) { "occurrence-${ids.size + 1}" }

    private fun coordinateFor(occurrence: ResolverOccurrenceId): String? {
        coordinates[occurrence]?.let { return it }
        val field = occurrencesById[occurrence] ?: return null
        if (!coordinatesInProgress.add(occurrence)) return null
        return try {
            coordinate(field.root, field.path).also { coordinates[occurrence] = it }
        } finally {
            coordinatesInProgress.remove(occurrence)
        }
    }

    fun path(
        root: CoverageRoot,
        path: List<PathComponent>,
    ): String = encodePath(root.label, path)

    fun coordinate(
        root: CoverageRoot,
        path: List<PathComponent>,
    ): String = encodePath(root.stableLabel, path)

    private fun encodePath(
        rootLabel: String,
        path: List<PathComponent>,
    ): String =
        PropertyTestJson.write(
            mapOf(
                "root" to rootLabel,
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
                "owner" to value.instanceId?.resolverOccurrenceId?.let(::coordinateFor),
            )
            is Map<*, *> -> value.entries.associate { it.key.toString() to expression(it.value) }
            is List<*> -> value.map(::expression)
            null -> null
            else -> value.toString()
        }
}

private enum class CoverageRootKind(
    val prefix: String,
    val priority: Int,
) {
    OPERATION_RESULT("result", 0),
    QUERY_FRAGMENT("queryFragment", 1),
    QUERY_OER("queryOer", 2),
    PROVIDER("providerRoot", 3),
    CONSUMER("consumerRoot", 4),
}

private data class CoverageRootRole(val kind: CoverageRootKind, val stableKey: String)

private class CoverageRoot(
    val result: ObjectEngineResult,
) {
    private val roles = linkedMapOf<CoverageRootKind, MutableSet<String>>()
    private val indices = mutableMapOf<CoverageRootKind, Int>()
    private val sortSegments = mutableListOf<String>()

    fun addRole(role: CoverageRootRole) {
        roles.getOrPut(role.kind) { linkedSetOf() } += role.stableKey
    }

    fun setIndex(
        kind: CoverageRootKind,
        index: Int,
    ) {
        indices[kind] = index
    }

    fun addSortSegment(segment: String) {
        sortSegments += segment
    }

    val kinds: Set<CoverageRootKind> get() = roles.keys
    val stableLabel: String get() = kinds.sortedBy(CoverageRootKind::priority).joinToString("+") { it.prefix }
    val stableSortPriority: Int get() = roles.keys.minOf(CoverageRootKind::priority)
    val stableSignature: String get() = sortSegments.joinToString("\u0000")
    val stableSortKey: String
        get() = roles.flatMap { (kind, stableKeys) -> stableKeys.map { "${kind.priority}:$it" } }
            .sorted()
            .joinToString("\u0000")

    val label: String
        get() =
            roles.keys.sortedBy(CoverageRootKind::priority).joinToString("+") { kind ->
                if (kind == CoverageRootKind.OPERATION_RESULT) kind.prefix else "${kind.prefix}-${requireNotNull(indices[kind])}"
            }

    fun hasValidatedSourceProvenance(): Boolean {
        return CoverageRootKind.OPERATION_RESULT in roles
    }
}

private data class CoverageField(
    val root: CoverageRoot,
    val key: ObjectEngineResult.ObjectKey,
    val path: List<PathComponent>,
    val value: EngineResult?,
)

private fun Arguments.argumentState(): ArgumentState =
    when {
        this == Arguments.Error -> ArgumentState.ERROR
        this is Arguments.Resolved && fieldValues.isEmpty() -> ArgumentState.NONE
        this is Arguments.Resolved && fieldValues.values.any(::containsNull) -> ArgumentState.CONTAINS_NULL
        else -> ArgumentState.RESOLVED
    }

private fun ViaductSchema.ObjectField.declaredOutputShape(): DeclaredOutputShape =
    when {
        type.isList -> DeclaredOutputShape.LIST
        type.baseTypeDef is ViaductSchema.CompositeTypeDef -> DeclaredOutputShape.OBJECT
        else -> DeclaredOutputShape.SCALAR
    }

private fun EngineResult?.outputKind(): OutputKind =
    when (this) {
        null -> OutputKind.NULL
        is ErrorEngineResult -> OutputKind.ERROR
        is ObjectEngineResult -> OutputKind.OBJECT
        is ListEngineResult -> OutputKind.LIST
        else -> OutputKind.SCALAR
    }

private fun ProviderFragment.bindingSource(): ResolverBindingSource =
    when (this) {
        ProviderFragment.OBJECT -> ResolverBindingSource.OBJECT_FIELD
        ProviderFragment.QUERY -> ResolverBindingSource.QUERY_FIELD
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
            is VariableDefinition.FromField -> source.providerFragment.bindingSource()
        }
    }

@VisibleForTest
fun Iterable<VariableDefinition.FromField>.maxProviderPathDepth(): Int? = maxOfOrNull { it.path.size }
