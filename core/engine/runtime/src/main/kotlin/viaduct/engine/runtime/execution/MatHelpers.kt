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
import viaduct.engine.runtime.EngineExecutionContextExtensions.fieldRssOriginFilteringKillSwitchEnabled
import viaduct.engine.runtime.HasResolver
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.KeyTreeFilter

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

/**
 * Converts the executable selections nested under [field] to their exact field keys.
 *
 * [outputSelectionSetFilter] clamps those keys to a resolver's schema-defined output selection
 * set and defaults to keeping every key. Required selection sets are not traversed.
 */
internal fun QueryPlan.keyTree(
    parameters: ExecutionParameters,
    field: QueryPlan.CollectedField,
    outputSelectionSetFilter: KeyTreeFilter = KeyTreeFilter.KeepAll,
): KeyTree =
    field.selectionSet?.let {
        keyTree(
            parameters = parameters,
            selectionSet = it,
        ).filter(outputSelectionSetFilter)
    } ?: KeyTree.empty

/** A collected field's schema definition and coerced arguments. */
internal data class ResolvedField(
    val fieldDefinition: GraphQLFieldDefinition,
    val arguments: Map<String, Any?>,
)

/** Resolves this field using execution state from [parameters] for [parentType]. */
internal fun QueryPlan.CollectedField.resolveField(
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
internal fun QueryPlan.CollectedField.resolveField(
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
): KeyTree {
    val composite = projectionType ?: selectionSet.parentType
    val fieldsByType = mutableMapOf<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>()
    for (type in parameters.engineExecutionContext.activeSchema.rels.possibleObjectTypes(composite)) {
        val fields = keyTreeForType(parameters, selectionSet, type)
        if (fields.isNotEmpty()) fieldsByType[type] = fields
    }
    return KeyTree(fieldsByType)
}

private fun QueryPlan.keyTreeForType(
    parameters: ExecutionParameters,
    selectionSet: QueryPlan.SelectionSet,
    type: GraphQLObjectType,
): Map<ObjectEngineResult.Key, KeyTree> {
    val collected = parameters.constants.collectCache.collect(
        schema = parameters.graphQLSchema,
        selectionSet = selectionSet,
        variables = parameters.coercedVariables,
        parentType = type,
        fragments = fragments,
        fieldRssOriginFilteringKillSwitchEnabled =
            parameters.engineExecutionContext.fieldRssOriginFilteringKillSwitchEnabled,
    )
    val fields = mutableMapOf<ObjectEngineResult.Key, KeyTree>()
    for (selection in collected.selections) {
        val field = selection as QueryPlan.CollectedField
        val resolvedField = field.resolveField(parameters, type)
        val children = field.selectionSet?.let {
            keyTree(
                parameters = parameters,
                selectionSet = it,
            )
        } ?: KeyTree.empty
        val key = field.oerKey(resolvedField.arguments)
        fields[key] = fields[key]?.plus(children) ?: children
    }
    return fields
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

/** A [KeyTreeFilter] that clamps a field resolvers subtree to its output selection set*/
@JvmInline
internal value class FieldOutputSelectionSetFilter(val hasResolver: HasResolver) : KeyTreeFilter {
    override fun invoke(
        type: GraphQLObjectType,
        key: ObjectEngineResult.Key,
        topLevel: Boolean
    ): Boolean =
        when {
            key.name.startsWith("__") -> false
            hasResolver(type, key.name) -> false
            else -> true
        }
}

/** A [KeyTreeFilter] that clamps a node resolvers subtree to its output selection set*/
@JvmInline
internal value class NodeOutputSelectionSetFilter(val hasResolver: HasResolver) : KeyTreeFilter {
    override fun invoke(
        type: GraphQLObjectType,
        key: ObjectEngineResult.Key,
        topLevel: Boolean
    ): Boolean =
        when {
            key.name.startsWith("__") -> false
            topLevel && key.name == "id" -> false
            hasResolver(type, key.name) -> false
            else -> true
        }
}

/**
 * Excludes fields that never require a node resolver while preserving resolver-owned fields that
 * still require the initial node resolution lifecycle to settle the reference.
 */
internal val nodeInitialResolutionFilter = KeyTreeFilter { _, key, topLevel ->
    !key.name.startsWith("__") && !(topLevel && key.name == "id")
}
