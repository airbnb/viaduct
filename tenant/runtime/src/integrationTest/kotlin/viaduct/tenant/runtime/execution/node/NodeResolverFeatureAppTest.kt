@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.node

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.execution.node.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class NodeResolverFeatureAppTest : FeatureAppTestBase() {
    override var sdl =
        """
        | #START_SCHEMA
        | type NodeObj implements Node @resolver {
        |     id: ID!
        |     value: String!
        | }
        |
        | type ObjectWithNodeField {
        |     node: NodeObj
        | }
        |
        | extend type Query {
        |     nodeObj(id: String!): NodeObj! @resolver
        |     nodeReference(id: String!): NodeObj! @resolver
        |     objectWithNodeField: ObjectWithNodeField @resolver
        | }
        | #END_SCHEMA
        """.trimMargin()

    // Tenant provided resolvers

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

    // Test Cases Start here
    @Test
    fun `Resolver returns the new GlobalID structured type (not the old string alias)`() {
        val generatedId = createGlobalIdString(NodeObj.Reflection, "tenant1")

        execute(
            query = """
                query TestQuery {
                    nodeObj(id: "tenant1") {
                        id
                        value
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "nodeObj" to {
                    "id" to generatedId
                    "value" to "tenant1"
                }
            }
        }
    }

    @Test
    fun `Resolver returns a node reference`() {
        val generatedId = createGlobalIdString(NodeObj.Reflection, "tenant1")

        execute(
            query = """
                query TestQuery {
                    nodeReference(id: "tenant1") {
                        id
                        value
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "nodeReference" to {
                    "id" to generatedId
                    "value" to "foo"
                }
            }
        }
    }

    @Test
    fun `Resolver returns a nested node reference`() {
        // This is a regression test for resolvers that return a node reference
        // for an object field
        val generatedId = createGlobalIdString(NodeObj.Reflection, "nestedNode")

        execute(
            query = """
                query TestQuery {
                    objectWithNodeField {
                        node {
                            id
                            value
                        }
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "objectWithNodeField" to {
                    "node" to {
                        "id" to generatedId
                        "value" to "foo"
                    }
                }
            }
        }
    }
}
