package viaduct.engine.runtime

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLUnionType
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.RootFieldReference

/**
 * Runtime implementation of [RootFieldReference] and [LazyAbstractData] for
 * interface/union-typed root fields.
 */
class AbstractRootFieldReference(
    override val rootFieldPath: List<String>,
    override val type: GraphQLCompositeType,
    override val args: Map<String, Any?>,
) : RootFieldReference, LazyAbstractData {
    init {
        require(type is GraphQLInterfaceType || type is GraphQLUnionType) {
            "AbstractRootFieldReference.type must be an interface or union type, got: ${type.name}. " +
                "Use ObjectRootFieldReference for object-typed root fields."
        }
    }

    private val resolveOnce = ResolveOnce()

    override suspend fun resolveData(
        selections: EngineSelectionSet,
        context: EngineExecutionContext,
    ): EngineObjectData =
        resolveOnce.resolve {
            RootFieldReferenceHelpers.resolveRootFieldReference(rootFieldPath, args, selections, context)
        }
}
