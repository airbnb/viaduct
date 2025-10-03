@file:Suppress("unused", "ClassName")

package viaduct.tenant.tutorial05

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.tutorial05.resolverbases.MutationResolvers
import viaduct.tenant.tutorial05.resolverbases.QueryResolvers

/**
 * LEARNING OBJECTIVES:
 * - Understand GraphQL mutations for data modification
 * - Learn ID extraction from mutation results for chaining operations
 * - See Node Resolver integration with mutations
 * - Master create/update/query patterns
 *
 * VIADUCT FEATURES DEMONSTRATED:
 * - Mutation Resolvers (MutationResolvers.CreateUser, UpdateUser)
 * - Node Resolvers working with mutations
 * - GlobalID round-trip patterns (create -> extract -> query)
 * - User.Builder for object construction
 *
 * CONCEPTS COVERED:
 * - Create, Read, Update patterns in GraphQL
 * - ID extraction from GraphQL responses
 * - Error handling in mutations vs queries
 * - Integration between mutations and Node Resolvers
 *
 * PREVIOUS: [viaduct.tenant.tutorial04.SimpleBackingDataFeatureAppTest]
 * NEXT: [viaduct.tenant.tutorial06.SimpleScopesFeatureAppTest]
 */
class SimpleMutationsFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        | type User implements Node @resolver {  # Node interface for GlobalID system
        |   id: ID!
        |   name: String
        |   email: String
        | }
        |
        | input UserInput {               # Input type for mutations
        |   name: String!
        |   email: String!
        | }
        |
        | extend type Query {
        |   user(id: String!): User @resolver
        | }
        |
        | extend type Mutation {         # Mutation operations
        |   createUser(input: UserInput!): User @resolver     # MutationResolvers.CreateUser()
        |   updateUser(id: String!, input: UserInput!): User @resolver   # MutationResolvers.UpdateUser()
        | }
        | #END_SCHEMA
    """.trimMargin()

    companion object {
        // TEST-ONLY DATA STORAGE - In production, replace with database
        private val users = ConcurrentHashMap<String, Pair<String, String>>() // id -> (name, email)
        private val nextId = AtomicInteger(1)
    }

    @BeforeEach
    fun cleanUp() {
        // TEST SETUP - Clear data between tests
        users.clear()
        nextId.set(1)
    }

    /**
     * NODE RESOLVER - Handles User object creation by GlobalID
     *
     * What YOU write:
     * - Implement resolve() to fetch/create User objects from GlobalIDs
     * - Use your data source (database, service, etc.)
     * - Return User objects via User.Builder
     *
     * What VIADUCT generates:
     * - NodeResolvers.User() base class
     * - Context with typed GlobalID (ctx.id)
     * - User.Builder for object construction
     */
    class UserNodeResolver : NodeResolvers.User() { // Generated from "type User implements Node @resolver"
        override suspend fun resolve(ctx: Context): User {
            val internalId = ctx.id.internalID

            // YOUR BUSINESS LOGIC - typically database lookup
            // In production: userRepository.findById(internalId)
            val (name, email) = users[internalId] // Test data
                ?: throw IllegalArgumentException("User not found: $internalId")

            return User.Builder(ctx)
                .id(ctx.id)
                .name(name)
                .email(email)
                .build()
        }
    }

    /**
     * QUERY RESOLVER - Standard Node Resolver integration
     */
    @Resolver
    class userResolver : QueryResolvers.User() { // Generated from query field
        override suspend fun resolve(ctx: Context): User? {
            return try {
                ctx.nodeFor(ctx.globalIDFor(User.Reflection, ctx.arguments.id))
            } catch (e: IllegalArgumentException) {
                null // GraphQL handles null gracefully
            }
        }
    }

    /**
     * CREATE MUTATION RESOLVER - Demonstrates creation pattern
     *
     * What YOU write:
     * - Generate/assign unique IDs
     * - Store/persist data using your data layer
     * - Return User objects with proper GlobalIDs
     *
     * What VIADUCT generates:
     * - MutationResolvers.CreateUser() base class
     * - Context with typed arguments (ctx.arguments.input)
     * - Input validation and parsing
     */
    @Resolver
    class CreateUserResolver : MutationResolvers.CreateUser() { // Generated from mutation field
        override suspend fun resolve(ctx: Context): User {
            val input = ctx.arguments.input

            // YOUR BUSINESS LOGIC - typically:
            // val newUser = userRepository.create(input.name, input.email)
            // val newId = newUser.id

            // TEST SIMULATION - Generate ID and store
            val newId = "user-${nextId.getAndIncrement()}"
            users[newId] = Pair(input.name, input.email)

            // RETURN WITH GLOBALID - Critical for client operations
            return User.Builder(ctx)
                .id(ctx.globalIDFor(User.Reflection, newId))
                .name(input.name)
                .email(input.email)
                .build()
        }
    }

    /**
     * UPDATE MUTATION RESOLVER - Demonstrates update pattern
     *
     * What YOU write:
     * - Validate the object exists
     * - Update data in your data layer
     * - Return updated object or null if not found
     */
    @Resolver
    class UpdateUserResolver : MutationResolvers.UpdateUser() { // Generated from mutation field
        override suspend fun resolve(ctx: Context): User? {
            val input = ctx.arguments.input
            val id = ctx.arguments.id

            // YOUR BUSINESS LOGIC - typically:
            // val updated = userRepository.update(id, input.name, input.email)
            // return if (updated) { ... } else null

            // TEST SIMULATION - Check and update
            val updated = users.computeIfPresent(id) { _, _ ->
                Pair(input.name, input.email)
            }

            return if (updated != null) {
                User.Builder(ctx)
                    .id(ctx.globalIDFor(User.Reflection, id))
                    .name(input.name)
                    .email(input.email)
                    .build()
            } else {
                null // User not found - mutation returns null
            }
        }
    }

    @Test
    fun `creates a new user and demonstrates ID extraction`() {
        val result = execute(
            query = """
                mutation {
                    createUser(input: {
                        name: "John Doe"
                        email: "john@example.com"
                    }) {
                        id      # GlobalID returned by mutation
                        name
                        email
                    }
                }
            """.trimIndent()
        )

        // ID EXTRACTION PATTERN - Critical for follow-up operations
        val createdUserId = result.getData<Map<String, Any>>()
            ?.get("createUser")?.let { it as Map<*, *> }
            ?.get("id") as String

        result.assertEquals {
            "data" to {
                "createUser" to {
                    "id" to createdUserId // GlobalID (encoded)
                    "name" to "John Doe"
                    "email" to "john@example.com"
                }
            }
        }

        // ROUND-TRIP TEST - Use extracted ID in query
        val globalId = getInternalId<User>(createdUserId)
        execute(
            query = """
                query {
                    user(id: "$globalId") {
                        name
                        email
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "user" to {
                    "name" to "John Doe"
                    "email" to "john@example.com"
                }
            }
        }
    }

    @Test
    fun `updates existing user with proper ID handling`() {
        // CREATE FIRST
        val createResult = execute(
            query = """
                mutation {
                    createUser(input: {
                        name: "Jane Doe"
                        email: "jane@example.com"
                    }) {
                        id
                    }
                }
            """.trimIndent()
        )

        val createdUserId = createResult.getData<Map<String, Any>>()
            ?.get("createUser")?.let { it as Map<*, *> }
            ?.get("id") as String

        val globalId = getInternalId<User>(createdUserId)

        // UPDATE WITH EXTRACTED ID
        execute(
            query = """
                mutation {
                    updateUser(id: "$globalId", input: {
                        name: "Jane Smith"
                        email: "jane.smith@example.com"
                    }) {
                        id
                        name
                        email
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "updateUser" to {
                    "id" to createdUserId // Same GlobalID as creation
                    "name" to "Jane Smith"
                    "email" to "jane.smith@example.com"
                }
            }
        }
    }

    @Test
    fun `demonstrates complete CRUD flow`() {
        // CREATE
        val createResult = execute(
            query = """
                mutation {
                    createUser(input: {
                        name: "Bob Wilson"
                        email: "bob@example.com"
                    }) {
                        id
                    }
                }
            """.trimIndent()
        )

        val createdUserId = createResult.getData<Map<String, Any>>()
            ?.get("createUser")?.let { it as Map<*, *> }
            ?.get("id") as String

        val globalId = getInternalId<User>(createdUserId)

        // READ
        execute(
            query = """
                query {
                    user(id: "$globalId") {
                        id
                        name
                        email
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "user" to {
                    "id" to createdUserId
                    "name" to "Bob Wilson"
                    "email" to "bob@example.com"
                }
            }
        }
    }

    /**
     * EXECUTION FLOW WALKTHROUGH:
     *
     * Mutation: createUser(input: {...})
     *
     * 1. CreateUserResolver.resolve() called with input arguments
     * 2. Generate unique ID and store data (your business logic)
     * 3. Create GlobalID: ctx.globalIDFor(User.Reflection, newId)
     * 4. Build User object with GlobalID and return
     * 5. Client receives encoded GlobalID for future operations
     *
     * Query: user(id: "user-1")
     *
     * 1. userResolver converts string to GlobalID
     * 2. ctx.nodeFor() routes to UserNodeResolver
     * 3. Extract internal ID from GlobalID
     * 4. Fetch data from your data source
     * 5. Build and return User object
     *
     * KEY TAKEAWAYS:
     * - Mutations create/modify data and return proper GlobalIDs
     * - ID extraction enables chaining operations
     * - Node Resolvers work seamlessly with mutation results
     * - Use User.Builder for consistent object construction
     * - Error handling: null returns vs exceptions
     */
}
