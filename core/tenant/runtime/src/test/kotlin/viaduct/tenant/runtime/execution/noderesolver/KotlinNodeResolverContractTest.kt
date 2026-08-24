@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.noderesolver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.resolver.Resolver
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
            return ctx.nodeRef(ctx.globalIDFor(NodeObj.Reflection, ctx.arguments.id))
        }
    }

    @Resolver
    class ObjectWithNodeFieldResolver : QueryResolvers.ObjectWithNodeField() {
        override suspend fun resolve(ctx: Context): ObjectWithNodeField? {
            return ObjectWithNodeField.Builder(ctx)
                .node(ctx.nodeRef(ctx.globalIDFor(NodeObj.Reflection, "nestedNode")))
                .build()
        }
    }

    @Resolver
    class NodeObjResolver : NodeResolvers.NodeObj() {
        companion object {
            var shouldReturnNodeReference = false
        }

        override suspend fun resolve(ctx: Context): NodeObj {
            if (shouldReturnNodeReference) {
                return ctx.nodeRef(ctx.globalIDFor(NodeObj.Reflection, "tenant1"))
            }
            return NodeObj.Builder(ctx).value("foo").build()
        }
    }

    @Resolver
    class NodeRefWithIllegalAccessResolver : QueryResolvers.NodeRefWithIllegalAccess() {
        override suspend fun resolve(ctx: Context): NodeObj {
            val ref = ctx.nodeRef(ctx.globalIDFor(NodeObj.Reflection, "1"))
            ref.getIdOrThrow() // valid — id can always be read
            ref.getValueOrThrow() // illegal — must throw
            return ref
        }
    }

    @Test
    fun `node resolver may not return a NodeReference`() {
        NodeObjResolver.shouldReturnNodeReference = true
        try {
            val result = execute(
                query = """
                    query TestQuery {
                        nodeReference(id: "tenant1") {
                            id
                            value
                        }
                    }
                """.trimIndent()
            )

            assertEquals(1, result.errors.size)
            val error = result.errors.single()
            assertTrue(error.message.contains("NodeReference returned from node resolver"))
            assertEquals("viaduct.errors.TenantUsageException", error.extensions["fullyQualifiedErrorClass"])
            assertEquals("NodeObj", error.extensions["resolvers"])
        } finally {
            NodeObjResolver.shouldReturnNodeReference = false
        }
    }
}
