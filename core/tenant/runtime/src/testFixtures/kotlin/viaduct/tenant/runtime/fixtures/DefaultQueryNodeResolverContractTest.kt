package viaduct.tenant.runtime.fixtures

import org.junit.jupiter.api.Test
import viaduct.graphql.test.assertEquals
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

/**
 * Contract test for the built-in Query.node and Query.nodes resolvers.
 *
 * Defines the SDL and assertions for:
 * - Query.node resolver is available by default for types implementing Node
 * - Query.nodes resolver is available by default for types implementing Node
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
abstract class DefaultQueryNodeResolverContractTest : FeatureAppTestBase() {
    private val codec = GlobalIDCodecDefault

    protected fun testUserGlobalId(internalId: String): String = codec.serialize("TestUser", internalId)

    init {
        sdl = """
            | #START_SCHEMA
            | "Node resolver: given a global ID for \"TestUser\", return TestUser with id=<globalId>, name=\"user name\""
            | type TestUser implements Node @resolver {
            |     id: ID!
            |     name: String!
            | }
            |
            | #END_SCHEMA
        """.trimMargin()
    }

    @Test
    fun `Query node has a built in resolver by default`() {
        val generatedId = testUserGlobalId("123")
        execute(
            query = """
                    query TestQuery {
                        node(id: "$generatedId") {
                            ... on TestUser {
                                id
                                name
                            }
                        }
                    }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "node" to {
                    "id" to generatedId
                    "name" to "user name"
                }
            }
        }
    }

    @Test
    fun `Query nodes has a built in resolver by default`() {
        val generatedId = testUserGlobalId("123")
        execute(
            query = """
                    query TestQuery {
                        nodes(ids: ["$generatedId"]) {
                            ... on TestUser {
                                id
                                name
                            }
                        }
                    }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "nodes" to arrayOf(
                    {
                        "id" to generatedId
                        "name" to "user name"
                    }
                )
            }
        }
    }
}
