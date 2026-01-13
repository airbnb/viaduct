package viaduct.graphql.schema.graphqljava

import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.test.SchemaDiff

/**
 * Round-trip tests for ToGraphQLSchema conversion with complex, realistic schemas.
 *
 * These tests complement GJRoundTripTest by testing more comprehensive real-world schema patterns.
 * Basic type conversions, nullability, directives, etc. are covered by GJRoundTripTest.
 */
class ToGraphQLSchemaRoundTripTest {
    @Test
    fun `realistic user management schema`() {
        val sdl = """
            enum Role { ADMIN, USER, GUEST }
            enum Status { ACTIVE, SUSPENDED, DELETED }

            interface Node { id: ID! }
            interface Timestamped { createdAt: String!, updatedAt: String }

            type User implements Node & Timestamped {
                id: ID!
                createdAt: String!
                updatedAt: String
                email: String!
                name: String
                role: Role!
                status: Status!
                posts: [Post!]!
            }

            type Post implements Node & Timestamped {
                id: ID!
                createdAt: String!
                updatedAt: String
                title: String!
                content: String
                author: User!
            }

            input CreateUserInput {
                email: String!
                name: String
                role: Role = USER
            }

            input UpdateUserInput {
                name: String
                role: Role
                status: Status
            }

            type Query {
                user(id: ID!): User
                users(status: Status, limit: Int = 10): [User!]!
            }

            type Mutation {
                createUser(input: CreateUserInput!): User!
                updateUser(id: ID!, input: UpdateUserInput!): User
                deleteUser(id: ID!): Boolean!
            }
        """.trimIndent()
        roundTrip(sdl, setOf("ID", "Int"))
    }

    @Test
    fun `schema with all type kinds`() {
        val sdl = """
            scalar DateTime

            enum Priority { LOW, MEDIUM, HIGH }

            interface Identifiable { id: ID! }

            type Task implements Identifiable {
                id: ID!
                title: String!
                priority: Priority!
                dueDate: DateTime
            }

            type Event implements Identifiable {
                id: ID!
                name: String!
                date: DateTime!
            }

            union CalendarItem = Task | Event

            input TaskFilter {
                priority: Priority
                dueBefore: DateTime
            }

            type Query {
                task(id: ID!): Task
                tasks(filter: TaskFilter): [Task!]!
                calendarItems: [CalendarItem!]!
            }
        """.trimIndent()
        roundTrip(sdl, setOf("ID", "DateTime"))
    }

    companion object {
        private fun roundTrip(
            sdl: String,
            scalarsNeeded: Set<String> = emptySet(),
        ) {
            val tdr = SchemaParser().parse(sdl)
            val expectedGraphQLSchema = SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING)
            val expectedViaductSchema = GJSchema.fromSchema(expectedGraphQLSchema)

            val actualGraphQLSchema = expectedViaductSchema.toGraphQLSchema(scalarsNeeded)
            val actualViaductSchema = GJSchema.fromSchema(actualGraphQLSchema)

            val schemaDiffResult = SchemaDiff(expectedViaductSchema, actualViaductSchema).diff()
            schemaDiffResult.assertEmpty()

            val extraDiffResult = GraphQLSchemaExtraDiff(expectedGraphQLSchema, actualGraphQLSchema).diff()
            extraDiffResult.assertEmpty()
        }
    }
}
