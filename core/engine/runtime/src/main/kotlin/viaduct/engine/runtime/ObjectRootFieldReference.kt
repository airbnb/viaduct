package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.RootFieldReference
import viaduct.engine.runtime.EngineExecutionContextExtensions.asImpl

/**
 * Runtime implementation of [RootFieldReference] and [LazyEngineObjectData] for object-typed
 * root fields.
 */
class ObjectRootFieldReference(
    override val rootFieldPath: List<String>,
    override val type: GraphQLObjectType,
    override val args: Map<String, Any?>,
) : RootFieldReference, LazyEngineObjectData {
    private val resolveOnce = ResolveOnce<EngineObjectData?>()

    override suspend fun fetch(selection: String): Any? {
        val resolved = resolveOnce.await() ?: return null
        return resolved.fetch(selection)
    }

    override suspend fun fetchOrNull(selection: String): Any? {
        val resolved = resolveOnce.await() ?: return null
        return resolved.fetchOrNull(selection)
    }

    override suspend fun fetchSelections(): Iterable<String> {
        val resolved = resolveOnce.await() ?: return emptyList()
        return resolved.fetchSelections()
    }

    /**
     * Resolves this reference once through the engine attached to [context].
     *
     * The engine executes the referenced root field and returns its original
     * [EngineObjectData], which remains the backing source for subsequent child-field resolution.
     */
    override suspend fun resolveData(
        selections: EngineSelectionSet,
        context: EngineExecutionContext,
    ): EngineObjectData? =
        resolveOnce.resolve {
            context.asImpl().resolveRootFieldReference(
                rootFieldPath = rootFieldPath,
                arguments = args,
                selectionSet = selections,
            )
        }
}
