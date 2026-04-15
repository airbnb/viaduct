package viaduct.tenant.runtime.execution.noderesolver

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

/**
 * Contract test for GlobalID-based node resolver patterns.
 *
 * Defines the SDL and assertions for:
 * - Resolver returns GlobalID structured type
 * - Resolver returns a node reference via ctx.nodeRef()
 * - Resolver returns a nested node reference on an object field
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
@TestSchema(
    """
    type NodeObj implements Node @resolver {
        id: ID!
        value: String!
    }

    type ObjectWithNodeField {
        node: NodeObj
    }

    extend type Query {
        "Return NodeObj with id=GlobalID(\"NodeObj\", id), value=id"
        nodeObj(id: String!): NodeObj! @resolver
        "Return a node reference via ctx.nodeRef(\"NodeObj\", \"tenant1\"); resolved NodeObj has value=\"foo\""
        nodeReference(id: String!): NodeObj! @resolver
        "Return ObjectWithNodeField with node=nodeRef(\"NodeObj\", \"nestedNode\"); resolved node has value=\"foo\""
        objectWithNodeField: ObjectWithNodeField @resolver
    }
"""
)
abstract class NodeResolverContractTest : KotlinFeatureAppTestContractBase() {
    private val codec = GlobalIDCodecDefault

    protected fun nodeObjGlobalId(internalId: String): String = codec.serialize("NodeObj", internalId)

    @Test
    fun `Resolver returns the new GlobalID structured type (not the old string alias)`() {
        val generatedId = nodeObjGlobalId("tenant1")

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
        val generatedId = nodeObjGlobalId("tenant1")

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
        val generatedId = nodeObjGlobalId("nestedNode")

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
