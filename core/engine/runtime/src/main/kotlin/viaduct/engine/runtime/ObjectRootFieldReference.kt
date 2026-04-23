package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.RootFieldReference

/**
 * Runtime implementation of [RootFieldReference] and [LazyEngineObjectData] for
 * object-typed root fields.
 */
class ObjectRootFieldReference(
    override val rootFieldPath: List<String>,
    override val type: GraphQLObjectType,
    override val args: Map<String, Any?>,
) : RootFieldReference, LazyEngineObjectData {
    private val resolveOnce = ResolveOnce()

    override suspend fun fetch(selection: String): Any? = resolveOnce.await().fetch(selection)

    override suspend fun fetchOrNull(selection: String): Any? = resolveOnce.await().fetchOrNull(selection)

    override suspend fun fetchSelections(): Iterable<String> = resolveOnce.await().fetchSelections()

    override suspend fun resolveData(
        selections: EngineSelectionSet,
        context: EngineExecutionContext,
    ): EngineObjectData =
        resolveOnce.resolve {
            RootFieldReferenceHelpers.resolveRootFieldReference(rootFieldPath, args, selections, context)
        }
}
