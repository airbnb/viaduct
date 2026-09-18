package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.ResultPath
import graphql.introspection.Introspection
import graphql.language.SourceLocation
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import java.util.Locale
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.ResolutionPolicy
import viaduct.engine.runtime.EngineExecutionContextExtensions.dispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextExtensions.fieldRssOriginFilteringKillSwitchEnabled
import viaduct.engine.runtime.EngineExecutionContextExtensions.isResolverSelective
import viaduct.engine.runtime.EngineExecutionContextExtensions.matResolutionEnabled
import viaduct.engine.runtime.HasResolver
import viaduct.engine.runtime.MatSource
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.KeyTreeFilter
import viaduct.engine.runtime.mat.KeyTreeFilter.Result.DROP
import viaduct.engine.runtime.mat.KeyTreeFilter.Result.KEEP_AND_RECURSE
import viaduct.engine.runtime.mat.KeyTreeFilter.Result.KEEP_WITHOUT_CHILDREN
import viaduct.engine.runtime.mat.Mat
import viaduct.engine.runtime.mat.MatPath.Segment
import viaduct.engine.runtime.mat.MatResult
import viaduct.engine.runtime.result.ObjectEngineResult
import viaduct.errors.TenantException

/**
 * Converts the current executable selection set to its exact field keys.
 *
 * [outputSelectionSetFilter] clamps those keys to a resolver's schema-defined output selection
 * set and defaults to keeping every key. Required selection sets are not traversed.
 */
internal fun QueryPlan.keyTree(
    parameters: ExecutionParameters,
    outputSelectionSetFilter: KeyTreeFilter = KeyTreeFilter.KeepAll,
): KeyTree =
    keyTree(
        parameters = parameters,
        selectionSet = parameters.selectionSet,
        projectionType = parameters.currentObjectEngineResult.type,
    ).filter(outputSelectionSetFilter)
        .withoutEmptyTypeBranches()

/**
 * Converts the executable selections nested under [field] to their exact field keys.
 *
 * [outputSelectionSetFilter] clamps those keys to a resolver's schema-defined output selection
 * set and defaults to keeping every key. Required selection sets are not traversed.
 */
internal fun QueryPlan.keyTree(
    parameters: ExecutionParameters,
    field: CollectedField,
    outputSelectionSetFilter: KeyTreeFilter = KeyTreeFilter.KeepAll,
): KeyTree =
    field.selectionSet?.let {
        keyTree(
            parameters = parameters,
            selectionSet = it,
        ).filter(outputSelectionSetFilter)
            .withoutEmptyTypeBranches()
    } ?: KeyTree.empty

/** A collected field's schema definition and coerced arguments. */
internal data class ResolvedField(
    val fieldDefinition: GraphQLFieldDefinition,
    val arguments: Map<String, Any?>,
)

/** Resolves this field using execution state from [parameters] for [parentType]. */
internal fun CollectedField.resolveField(
    parameters: ExecutionParameters,
    parentType: GraphQLObjectType,
): ResolvedField =
    resolveField(
        schema = parameters.graphQLSchema,
        parentType = parentType,
        variables = parameters.coercedVariables,
        graphQLContext = parameters.executionContext.graphQLContext,
        locale = parameters.executionContext.locale,
    )

/**
 * Resolves this field's definition on [parentType] and coerces its argument values.
 */
internal fun CollectedField.resolveField(
    schema: GraphQLSchema,
    parentType: GraphQLObjectType,
    variables: CoercedVariables,
    graphQLContext: GraphQLContext,
    locale: Locale,
): ResolvedField {
    val fieldDefinition = Introspection.getFieldDef(schema, parentType, fieldName)
    return ResolvedField(
        fieldDefinition = fieldDefinition,
        arguments = FieldExecutionHelpers.resolveFieldArguments(
            schema.codeRegistry,
            fieldDefinition,
            mergedField,
            variables,
            graphQLContext,
            locale,
        ),
    )
}

internal fun QueryPlan.keyTree(
    parameters: ExecutionParameters,
    selectionSet: QueryPlan.SelectionSet,
    projectionType: GraphQLObjectType? = null,
): KeyTree =
    keyTree(
        schema = parameters.engineExecutionContext.activeSchema,
        context = QueryPlanFilterCtx(parameters),
        selectionSet = selectionSet,
        projectionType = projectionType,
    )

/** Converts executable selections to a [KeyTree] */
internal fun QueryPlan.keyTree(
    schema: EngineSchema,
    context: QueryPlanFilterCtx,
    selectionSet: QueryPlan.SelectionSet,
    projectionType: GraphQLObjectType? = null,
): KeyTree {
    val composite = projectionType ?: selectionSet.parentType
    val fieldsByType = mutableMapOf<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>()
    for (type in schema.rels.possibleObjectTypes(composite)) {
        val fields = keyTreeForType(
            schema,
            context,
            selectionSet,
            type,
        )
        if (
            fields.isNotEmpty() ||
            hasConditionallyExcludedSelectionForType(schema, context, selectionSet, type)
        ) {
            fieldsByType[type] = fields
        }
    }
    return KeyTree(fieldsByType)
}

private fun QueryPlan.hasConditionallyExcludedSelectionForType(
    schema: EngineSchema,
    context: QueryPlanFilterCtx,
    selectionSet: QueryPlan.SelectionSet,
    type: GraphQLObjectType,
): Boolean =
    ExecutionSelectionSet.create(
        schema = schema,
        typeName = type.name,
        selectionSet = selectionSet,
        fragments = fragments,
        variables = context.variables.toMap(),
        graphQLContext = context.graphQLContext,
        locale = context.locale,
        queryPlan = this,
        fieldRssOriginFilteringKillSwitchEnabled =
            context.fieldRssOriginFilteringKillSwitchEnabled,
        collectCache = context.collectCache,
    ).conditionallyExcludedResultKeys().isNotEmpty()

private fun QueryPlan.keyTreeForType(
    schema: EngineSchema,
    context: QueryPlanFilterCtx,
    selectionSet: QueryPlan.SelectionSet,
    type: GraphQLObjectType,
): Map<ObjectEngineResult.Key, KeyTree> {
    val collected = context.collectCache.collect(
        schema = context.schema,
        selectionSet = selectionSet,
        variables = context.variables,
        parentType = type,
        fragments = fragments,
        fieldRssOriginFilteringKillSwitchEnabled = context.fieldRssOriginFilteringKillSwitchEnabled,
    )
    val fields = mutableMapOf<ObjectEngineResult.Key, KeyTree>()
    for (field in collected) {
        val resolvedField = field.resolveField(
            schema = context.schema,
            parentType = type,
            variables = context.variables,
            graphQLContext = context.graphQLContext,
            locale = context.locale,
        )
        val children = field.selectionSet?.let {
            keyTree(
                schema = schema,
                context = context,
                selectionSet = it,
            )
        } ?: KeyTree.empty
        val key = field.oerKey(resolvedField.arguments)
        fields[key] = fields[key]?.plus(children) ?: children
    }
    return fields
}

/**
 * Returns a [MatSource.Embedded] when Mat resolution is enabled, the current object has a Mat
 * source, and either the field has no dispatcher under standard resolution or both the parent and
 * child are [ResolutionPolicy.PARENT_MANAGED]. Returns `null` otherwise.
 */
internal fun mkEmbeddedMatSource(
    parameters: ExecutionParameters,
    field: CollectedField,
    memberType: GraphQLObjectType,
    memberIndices: List<Int>,
    resolutionPolicy: ResolutionPolicy,
): MatSource? {
    if (!parameters.engineExecutionContext.matResolutionEnabled) return null
    val parentSource = parameters.currentObjectEngineResult.matSource ?: return null
    val parentManaged = parentSource.fieldResolutionPolicy == ResolutionPolicy.PARENT_MANAGED
    if (parentManaged && resolutionPolicy == ResolutionPolicy.STANDARD) return null
    val parentTypeName = parameters.executionStepInfo.objectType.name
    val dispatcher =
        parameters.engineExecutionContext.dispatcherRegistry
            .getFieldResolverDispatcher(parentTypeName, field.fieldName)

    if (dispatcher != null && !parentManaged) return null
    return MatSource.Embedded(
        parameters.currentObjectEngineResult,
        Segment(
            type = memberType,
            key = FieldExecutionHelpers.buildOERKeyForField(parameters, field),
            indices = memberIndices,
        ),
        fieldResolutionPolicy = resolutionPolicy,
    )
}

internal fun isFieldMatBacked(
    parameters: ExecutionParameters,
    field: CollectedField,
    effectiveData: Any?,
): Boolean {
    if (!parameters.engineExecutionContext.matResolutionEnabled) return false
    val parentPolicy = parameters.currentObjectEngineResult.matSource?.fieldResolutionPolicy ?: parameters.resolutionPolicy
    if (parentPolicy == ResolutionPolicy.PARENT_MANAGED) return false
    if (effectiveData !is EngineObjectData) return false

    val parentTypeName = parameters.executionStepInfo.objectType.name
    return parameters.engineExecutionContext.isResolverSelective(parentTypeName to field.fieldName)
}

internal fun isNodeMatBacked(
    parameters: ExecutionParameters,
    fieldType: GraphQLObjectType,
): Boolean {
    if (!parameters.engineExecutionContext.matResolutionEnabled) return false

    val dispatcher = parameters.engineExecutionContext.dispatcherRegistry
        .getNodeResolverDispatcher(fieldType.name)
        ?: return false
    return dispatcher.isSelective
}

internal fun materializationPlan(
    selectionParameters: ExecutionParameters,
    keyTree: KeyTree,
): QueryPlan {
    val materializationShape = keyTree.withoutEmptyTypeBranches()
    val plan =
        selectionParameters.queryPlan.filterTo(
            shape = materializationShape,
            context = QueryPlanFilterCtx(selectionParameters),
            source = selectionParameters.selectionSet,
            projectionType = selectionParameters.currentObjectEngineResult.type,
        )
    val projected =
        plan.keyTree(
            parameters = selectionParameters,
            selectionSet = plan.selectionSet,
            projectionType = selectionParameters.currentObjectEngineResult.type,
        )
    val missing = materializationShape - projected
    check(missing.isEmpty()) {
        "Materialization plan omitted requested selections $missing"
    }
    return plan
}

internal fun <T : Any> requireMaterializedNotNull(
    value: T?,
    message: () -> String
): T = value ?: throw materializationException(message())

internal fun materializationException(
    message: String,
    parameters: ExecutionParameters? = null,
    cause: Throwable? = null,
): RuntimeException {
    if (cause is InternalEngineException) return cause

    return InternalEngineException.wrapWithPathAndLocation(
        IllegalStateException(message, cause),
        parameters?.path ?: ResultPath.rootPath(),
        parameters?.field?.sourceLocation ?: SourceLocation.EMPTY,
    )
}

internal fun Mat.failedResultFor(
    keyTree: KeyTree,
    parameters: ExecutionParameters,
    cause: Exception,
): MatResult {
    val unwrappedCause = UnwrapExceptionUtil.unwrapExceptionForError(cause)
    val failure =
        when (unwrappedCause) {
            is TenantException -> unwrappedCause
            else ->
                materializationException(
                    buildString {
                        append("mat ${this@failedResultFor} failed when materialized for $keyTree")
                        cause.message?.let {
                            append(": ")
                            append(it)
                        }
                    },
                    parameters = parameters,
                    cause = cause,
                )
        }

    return MatResult(
        coverage = keyTree,
        source = Result.failure(failure),
    )
}

/** A [KeyTreeFilter] that clamps a field resolvers subtree to its output selection set*/
@JvmInline
internal value class FieldOutputSelectionSetFilter(val hasResolver: HasResolver) : KeyTreeFilter {
    override fun invoke(
        type: GraphQLObjectType,
        key: ObjectEngineResult.Key,
        topLevel: Boolean
    ): KeyTreeFilter.Result =
        when {
            key.name.startsWith("__") -> DROP
            hasResolver(type, key.name) -> DROP
            else -> KEEP_AND_RECURSE
        }
}

/** Existing child objects handle descendant reads through their own resolution policy. */
@JvmInline
internal value class ParentManagedReadTraversalFilter(val covered: KeyTree) : KeyTreeFilter {
    override fun invoke(
        type: GraphQLObjectType,
        key: ObjectEngineResult.Key,
        topLevel: Boolean,
    ): KeyTreeFilter.Result = if (topLevel && covered.containsKey(type, key)) KEEP_WITHOUT_CHILDREN else KEEP_AND_RECURSE
}

/** A [KeyTreeFilter] that clamps a node resolvers subtree to its output selection set*/
@JvmInline
internal value class NodeOutputSelectionSetFilter(val hasResolver: HasResolver) : KeyTreeFilter {
    override fun invoke(
        type: GraphQLObjectType,
        key: ObjectEngineResult.Key,
        topLevel: Boolean
    ): KeyTreeFilter.Result =
        when {
            key.name.startsWith("__") -> DROP
            topLevel && key.name == "id" -> DROP
            hasResolver(type, key.name) -> DROP
            else -> KEEP_AND_RECURSE
        }
}

/**
 * Excludes fields that never require a node resolver while preserving resolver-owned fields that
 * still require the initial node resolution lifecycle to settle the reference.
 */
internal val nodeInitialResolutionFilter = KeyTreeFilter { _, key, topLevel ->
    if (key.name.startsWith("__") || (topLevel && key.name == "id")) DROP else KEEP_AND_RECURSE
}
