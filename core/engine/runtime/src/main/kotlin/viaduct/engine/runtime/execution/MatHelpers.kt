package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.ResultPath
import graphql.introspection.Introspection
import graphql.language.SourceLocation
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import java.util.Locale
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.runtime.EngineExecutionContextExtensions.fieldRssOriginFilteringKillSwitchEnabled
import viaduct.engine.runtime.HasResolver
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.KeyTreeFilter

/** Converts the current executable selection set to its exact field keys. */
internal fun QueryPlan.keyTree(parameters: ExecutionParameters): KeyTree =
    keyTree(
        parameters = parameters,
        selectionSet = parameters.selectionSet,
        parentType = parameters.currentObjectEngineResult.type,
    )

/** Converts the executable selections nested under [field] to their exact field keys. */
internal fun QueryPlan.keyTree(
    parameters: ExecutionParameters,
    field: QueryPlan.CollectedField,
): KeyTree =
    field.selectionSet?.let {
        keyTree(
            parameters = parameters,
            selectionSet = it,
            parentType = parameters.executionStepInfo.fieldDefinition.type,
        )
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

private fun QueryPlan.keyTree(
    parameters: ExecutionParameters,
    selectionSet: QueryPlan.SelectionSet,
    parentType: GraphQLOutputType,
): KeyTree {
    val composite = GraphQLTypeUtil.unwrapAll(parentType) as? GraphQLCompositeType
        ?: return KeyTree.empty
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
                parentType = resolvedField.fieldDefinition.type,
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

/**
 * Returns the portion of this selection shape represented by [value].
 *
 * A field is retained when [EngineObjectData.fetchSelections] exposes its name. Nested
 * object values are filtered recursively. Null values preserve the requested child coverage
 * because there is no nested object from which more fields could be fetched. For example, when
 * `bar { x }` returns `bar = null`, the result still covers `bar { x }`, preventing the same request
 * from being retried. Iterable values narrow coverage only for concrete types that appear; requested
 * coverage for absent concrete types is preserved.
 */
internal suspend fun KeyTree.filterByEngineObjectData(value: EngineObjectData?): KeyTree {
    if (value == null || isEmpty()) return this
    if (value is NodeEngineObjectData) return fromNodeReference(value, this)

    val fields = keysByType()[value.type].orEmpty()
    if (fields.isEmpty()) return KeyTree.empty

    val availableSelections = value.fetchSelections()
    val returnedSelections =
        if (availableSelections is Set<*>) availableSelections else availableSelections.toSet()
    val nestedValues = mutableMapOf<String, Any?>()
    val result = mutableMapOf<ObjectEngineResult.Key, KeyTree>()
    for ((key, subShape) in fields) {
        // Resolver outputs always use schema field names, never response aliases.
        if (key.name !in returnedSelections) continue
        if (subShape.isEmpty()) {
            result[key] = KeyTree.empty
            continue
        }

        val nestedValue = if (nestedValues.containsKey(key.name)) {
            nestedValues[key.name]
        } else {
            value.fetchOrNull(key.name).also { nestedValues[key.name] = it }
        }
        result[key] = subShape.filterByValue(nestedValue)
    }

    return if (result.isEmpty()) {
        KeyTree.empty
    } else {
        KeyTree(mapOf(value.type to result))
    }
}

private suspend fun KeyTree.filterByValue(value: Any?): KeyTree =
    when (value) {
        null -> this
        is NodeEngineObjectData -> fromNodeReference(value, this)
        is EngineObjectData -> filterByEngineObjectData(value)
        is Iterable<*> -> filterByIterable(value)
        else -> KeyTree.empty
    }

private suspend fun KeyTree.filterByIterable(values: Iterable<*>): KeyTree {
    val coverageByType = mutableMapOf<GraphQLObjectType, KeyTree>()
    for ((type, fields) in keysByType()) {
        coverageByType[type] = KeyTree(mapOf(type to fields))
    }
    val exhaustedTypes = mutableSetOf<GraphQLObjectType>()

    suspend fun visit(value: Any?) {
        when (value) {
            null -> Unit
            is Iterable<*> -> value.forEach { visit(it) }
            is EngineObjectData -> {
                if (value.type in exhaustedTypes) return
                val previous = coverageByType[value.type] ?: return
                val coverage = filterByEngineObjectData(value)
                val commonCoverage = previous.intersect(coverage)
                if (commonCoverage.isEmpty()) {
                    coverageByType.remove(value.type)
                    exhaustedTypes += value.type
                } else {
                    coverageByType[value.type] = commonCoverage
                }
            }
        }
    }

    visit(values)
    return KeyTree(
        coverageByType.mapValues { (type, coverage) ->
            coverage.keysByType().getValue(type)
        }
    )
}

private fun fromNodeReference(
    value: NodeEngineObjectData,
    shape: KeyTree,
): KeyTree {
    if (shape.isEmpty()) return KeyTree.empty
    val fields = shape.keysByType()[value.type].orEmpty()
    if (fields.isEmpty()) return KeyTree.empty

    val result = fields
        .filterKeys { key -> key.name == "id" }
        .mapValues { KeyTree.empty }
    return if (result.isEmpty()) {
        KeyTree.empty
    } else {
        KeyTree(mapOf(value.type to result))
    }
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
