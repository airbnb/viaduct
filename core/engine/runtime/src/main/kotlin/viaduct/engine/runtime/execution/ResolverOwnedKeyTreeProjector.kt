package viaduct.engine.runtime.execution

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.ResolverType
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.mat.KeyTree

/** Projects an execution-backed selection set to the fields owned by one resolver. */
internal fun QueryPlan.projectResolverOwnedSelections(
    schema: ViaductSchema,
    context: QueryPlanFilterCtx,
    source: QueryPlan.SelectionSet,
    projectionType: GraphQLObjectType?,
    dispatcherRegistry: DispatcherRegistry,
    resolverType: ResolverType,
): QueryPlan {
    val ownedShape = keyTree(
        schema = schema,
        context = context,
        selectionSet = source,
        projectionType = projectionType,
    ).resolverOwnedShape(
        schema,
        dispatcherRegistry,
        resolverType,
    )
    return filterTo(
        shape = ownedShape,
        context = context,
        source = source,
        projectionType = projectionType,
    )
}

/**
 * Keeps exact requested keys owned by one resolver.
 *
 * A node type is a boundary below the resolver root. At the root it is also a boundary for field
 * resolvers, while a node resolver owns that root and excludes only its top-level `id`.
 */
internal fun KeyTree.resolverOwnedShape(
    schema: ViaductSchema,
    dispatcherRegistry: DispatcherRegistry,
    resolverType: ResolverType,
): KeyTree =
    ResolverOwnedKeyTreeProjector(
        schema,
        dispatcherRegistry,
        resolverType,
    ).project(this, topLevel = true)

private class ResolverOwnedKeyTreeProjector(
    private val schema: ViaductSchema,
    private val dispatcherRegistry: DispatcherRegistry,
    private val resolverType: ResolverType,
) {
    fun project(
        tree: KeyTree,
        topLevel: Boolean,
    ): KeyTree {
        val projectedByType = linkedMapOf<GraphQLObjectType, Map<ObjectEngineResult.Key, KeyTree>>()
        for ((type, fields) in tree.keysByType()) {
            projectFields(type, fields, topLevel)?.let {
                projectedByType[type] = it
            }
        }
        return KeyTree(projectedByType)
    }

    private fun projectFields(
        type: GraphQLObjectType,
        fields: Map<ObjectEngineResult.Key, KeyTree>,
        topLevel: Boolean,
    ): Map<ObjectEngineResult.Key, KeyTree>? {
        if (isTypeBoundary(type, topLevel)) return null

        val projectedFields = linkedMapOf<ObjectEngineResult.Key, KeyTree>()
        for ((key, children) in fields) {
            projectField(type, key, children, topLevel)?.let {
                projectedFields[key] = it
            }
        }
        return projectedFields.takeIf { it.isNotEmpty() || !topLevel }
    }

    private fun projectField(
        parentType: GraphQLObjectType,
        key: ObjectEngineResult.Key,
        children: KeyTree,
        topLevel: Boolean,
    ): KeyTree? {
        if (isOmittedField(parentType, key, topLevel)) return null

        val outputType = parentType.getFieldDefinition(key.name)
            ?.type
            ?.let(GraphQLTypeUtil::unwrapAll)
        if (isOutputBoundary(outputType, children)) return null

        return if (outputType is GraphQLCompositeType) {
            projectComposite(children, outputType)
        } else {
            children
        }
    }

    private fun isOmittedField(
        parentType: GraphQLObjectType,
        key: ObjectEngineResult.Key,
        topLevel: Boolean,
    ): Boolean =
        key.name.startsWith("__") ||
            isTopLevelNodeId(key, topLevel) ||
            dispatcherRegistry.getFieldResolverDispatcher(parentType.name, key.name) != null

    private fun isTopLevelNodeId(
        key: ObjectEngineResult.Key,
        topLevel: Boolean,
    ): Boolean =
        topLevel &&
            resolverType == ResolverType.NODE &&
            key.name == "id"

    private fun isOutputBoundary(
        outputType: GraphQLType?,
        children: KeyTree,
    ): Boolean =
        when (outputType) {
            is GraphQLObjectType -> isNodeBoundary(outputType)
            is GraphQLCompositeType -> candidateTypes(outputType, children).all(::isNodeBoundary)
            else -> false
        }

    private fun candidateTypes(
        outputType: GraphQLCompositeType,
        children: KeyTree,
    ): Iterable<GraphQLObjectType> {
        val selectedTypes = children.keysByType().keys
        return if (selectedTypes.isEmpty()) {
            schema.rels.possibleObjectTypes(outputType)
        } else {
            selectedTypes
        }
    }

    private fun projectComposite(
        children: KeyTree,
        outputType: GraphQLCompositeType,
    ): KeyTree =
        project(children, topLevel = false)
            .takeUnless { it.isEmpty() && children.isEmpty() }
            ?: validEmptyShape(outputType)

    private fun validEmptyShape(type: GraphQLCompositeType): KeyTree =
        KeyTree(
            schema.rels.possibleObjectTypes(type)
                .filterNot(::isNodeBoundary)
                .associateWith { emptyMap() }
        )

    private fun isTypeBoundary(
        type: GraphQLObjectType,
        topLevel: Boolean,
    ): Boolean =
        isNodeBoundary(type) &&
            (!topLevel || resolverType != ResolverType.NODE)

    private fun isNodeBoundary(type: GraphQLObjectType): Boolean = dispatcherRegistry.getNodeResolverDispatcher(type.name) != null
}
