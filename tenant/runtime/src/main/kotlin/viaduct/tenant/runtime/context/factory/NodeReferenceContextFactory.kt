package viaduct.tenant.runtime.context.factory

import viaduct.api.internal.NodeReferenceGRTFactory
import viaduct.engine.api.EngineExecutionContext
import viaduct.tenant.runtime.internal.NodeReferenceGRTFactoryImpl

object NodeReferenceContextFactory {
    /** A default Factory<NodeReferenceFactory> suitable for any use */
    val default: Factory<EngineExecutionContext, NodeReferenceGRTFactory> =
        Factory { engineExecutionContext ->
            NodeReferenceGRTFactoryImpl { id, graphQLObjectType ->
                engineExecutionContext.createNodeReference(
                    id,
                    graphQLObjectType,
                )
            }
        }
}
