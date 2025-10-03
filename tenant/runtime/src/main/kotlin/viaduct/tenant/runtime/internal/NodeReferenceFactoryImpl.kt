package viaduct.tenant.runtime.internal

import graphql.schema.GraphQLObjectType
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.NodeReferenceFactory
import viaduct.api.types.NodeObject
import viaduct.engine.api.NodeEngineObjectData
import viaduct.tenant.runtime.toGRT

/**
 * The canonical implementation of the `NodeReferenceFactory` interface.
 * Recall that `NodeReferenceFactory` is an interface for creating a reference
 * at an unresolved node.  To do so is to construct a Kotlin object of the
 * referenced type from the passed-in `InternalContext` instance and from the
 * result of invoking @nodeEngineObjectDataFactory, with the global ID of the
 * referenced node and its type as arguments.  So, for example, given a
 * `GlobalID<Wishlist>("Wishlist:1234")`, the `nodeFor` method will return an
 * instance of `Wishlist` that has ID 1234 in it, as well as the resolution
 * mechanism that @nodeEngineObjectDataFactory contains, to resolve its fields.
 */
class NodeReferenceFactoryImpl(
    private val nodeEngineObjectDataFactory: (String, GraphQLObjectType) -> NodeEngineObjectData,
) : NodeReferenceFactory {
    override fun <T : NodeObject> nodeFor(
        id: GlobalID<T>,
        internalContext: InternalContext
    ): T {
        val type = internalContext.schema.schema.getObjectType(id.type.name)
        val nodeEOD = nodeEngineObjectDataFactory(
            internalContext.globalIDCodec.serialize(id),
            type,
        )

        return NodeReferenceEngineObjectData(nodeEOD).toGRT(internalContext, id.type)
    }
}
