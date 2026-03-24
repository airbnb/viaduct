package viaduct.tenant.runtime.execution.defaultresolvers

import org.junit.jupiter.api.Test
import viaduct.tenant.runtime.execution.defaultresolvers.resolverbases.NodeResolvers
import viaduct.tenant.runtime.featuretests.fixtures.assertJson
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class DefaultQueryNodeResolverFeatureAppTest : FeatureAppTestBase() {
    override var sdl =
        """
        | #START_SCHEMA
        | type TestUser implements Node @resolver {
        |     id: ID!
        |     name: String!
        | }
        |
        | #END_SCHEMA
        """.trimMargin()

    // Tenant provided resolvers
    class TestFooResolver : NodeResolvers.TestUser() {
        override suspend fun resolve(ctx: Context): TestUser {
            return TestUser.Builder(ctx).id(ctx.id).name("user name").build()
        }
    }

    @Test
    fun `Query node has a built in resolver by default`() {
        val generatedId = createGlobalIdString(TestUser.Reflection, "123")
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
        ).assertJson(
            """
                {data: {node: {id: "$generatedId", name: "user name"}}}"
            """
        )
    }

    @Test
    fun `Query nodes has a built in resolver by default`() {
        val internalId = "123"
        val generatedId = createGlobalIdString(TestUser.Reflection, internalId)
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
        ).assertJson(
            """
                {data: {nodes: [{id: "$generatedId", name: "user name"}]}}"
            """
        )
    }
}
