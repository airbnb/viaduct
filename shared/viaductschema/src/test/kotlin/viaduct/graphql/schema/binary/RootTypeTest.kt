package viaduct.graphql.schema.binary

import org.junit.jupiter.api.Test

/**
 * Test encoding and decoding of schemas with custom root type names.
 * GraphQL allows schemas to use custom names for the query, mutation,
 * and subscription root types using the schema directive.
 */
class RootTypeTest {
    @Test
    fun `Standard root type names`() {
        val sdl = """
            type Query { field: String }
            type Mutation { field: String }
            type Subscription { field: String }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Query only with standard name`() {
        val sdl = """
            type Query { field: String }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Custom query type name only`() {
        val sdl = """
            schema {
                query: MyCustomQuery
            }
            type MyCustomQuery { field: String }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Custom query and mutation names`() {
        val sdl = """
            schema {
                query: MyQuery
                mutation: MyMutation
            }
            type MyQuery { field: String }
            type MyMutation { field: String }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Custom all three root type names`() {
        val sdl = """
            schema {
                query: MyCustomQuery
                mutation: MyCustomMutation
                subscription: MyCustomSubscription
            }
            type MyCustomQuery { field: String }
            type MyCustomMutation { field: String }
            type MyCustomSubscription { field: String }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Custom query name without mutation or subscription`() {
        val sdl = """
            schema {
                query: RootQuery
            }
            type RootQuery {
                hello: String
                world: Int
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Custom query and subscription without mutation`() {
        val sdl = """
            schema {
                query: QueryRoot
                subscription: SubscriptionRoot
            }
            type QueryRoot { field: String }
            type SubscriptionRoot { field: String }
        """.trimIndent()
        assertRoundTrip(sdl)
    }

    @Test
    fun `Complex schema with custom root names`() {
        val sdl = """
            schema {
                query: Q
                mutation: M
            }

            type Q {
                user(id: ID!): User
                posts: [Post]
            }

            type M {
                createUser(name: String!): User
                deleteUser(id: ID!): Boolean
            }

            type User {
                id: ID!
                name: String
                posts: [Post]
            }

            type Post {
                id: ID!
                title: String
                author: User
            }
        """.trimIndent()
        assertRoundTrip(sdl)
    }
}
