package viaduct.tenant.runtime.internal

import graphql.schema.GraphQLObjectType
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.internal.NodeReferenceGRTFactory
import viaduct.api.types.NodeObject
import viaduct.engine.api.NodeReference
import viaduct.tenant.runtime.toObjectGRT

/**
 * The canonical implementation of the `NodeReferenceGRTFactory` interface.
 * Recall that `NodeReferenceGRTFactory` is an interface for creating a GRT reference
 * at an unresolved node.  To do so is to construct a Kotlin object of the
 * referenced type from the passed-in `InternalContext` instance and from the
 * result of invoking @nodeReferenceFactory, with the global ID of the
 * referenced node and its type as arguments.  So, for example, given a
 * `GlobalID<Wishlist>("Wishlist:1234")`, the `nodeFor` method will return an
 * instance of `Wishlist` that has ID 1234 in it, as well as the resolution
 * mechanism that @nodeReferenceFactory contains, to resolve its fields.
 */
class NodeReferenceGRTFactoryImpl(
    private val nodeReferenceFactory: (String, GraphQLObjectType) -> NodeReference,
) : NodeReferenceGRTFactory {
    override fun <T : NodeObject> nodeFor(
        id: GlobalID<T>,
        internalContext: InternalContext
    ): T {
        val type = internalContext.schema.schema.getObjectType(id.type.name)
        val nodeReference = nodeReferenceFactory(
            internalContext.globalIDCodec.serialize(id.type.name, id.internalID),
            type,
        )

        return nodeReference.toObjectGRT(internalContext, id.type.kcls)
    }
}
