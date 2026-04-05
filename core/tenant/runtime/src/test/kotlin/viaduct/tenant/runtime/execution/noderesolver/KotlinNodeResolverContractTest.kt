@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.noderesolver

import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.noderesolver.resolverbases.NodeResolvers
import viaduct.tenant.runtime.execution.noderesolver.resolverbases.QueryResolvers

class KotlinNodeResolverContractTest : NodeResolverContractTest() {
    @Resolver
    class QueryNodeObjResolver : QueryResolvers.NodeObj() {
        override suspend fun resolve(ctx: Context): NodeObj {
            return NodeObj.Builder(ctx)
                .id(ctx.globalIDFor(NodeObj.Reflection, ctx.arguments.id))
                .value(ctx.arguments.id)
                .build()
        }
    }

    @Resolver
    class NodeReferenceResolver : QueryResolvers.NodeReference() {
        override suspend fun resolve(ctx: Context): NodeObj {
            return ctx.nodeFor(ctx.globalIDFor(NodeObj.Reflection, ctx.arguments.id))
        }
    }

    @Resolver
    class ObjectWithNodeFieldResolver : QueryResolvers.ObjectWithNodeField() {
        override suspend fun resolve(ctx: Context): ObjectWithNodeField? {
            return ObjectWithNodeField.Builder(ctx)
                .node(ctx.nodeFor(ctx.globalIDFor(NodeObj.Reflection, "nestedNode")))
                .build()
        }
    }

    class NodeObjResolver : NodeResolvers.NodeObj() {
        override suspend fun resolve(ctx: Context): NodeObj {
            return NodeObj.Builder(ctx).value("foo").build()
        }
    }
}
