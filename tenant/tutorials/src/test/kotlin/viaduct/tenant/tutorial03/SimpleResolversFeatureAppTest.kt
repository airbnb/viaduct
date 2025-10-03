@file:Suppress("unused", "ClassName")

package viaduct.tenant.tutorial03

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.graphql.test.assertHasError
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.tutorial03.resolverbases.QueryResolvers
import viaduct.tenant.tutorial03.resolverbases.UserResolvers

/**
 * Demonstrates Node Resolvers and Field Resolvers working together.
 *
 * This combines both patterns: Node Resolvers handle object creation by GlobalID,
 * while Field Resolvers compute individual field values. The objectValueFragment
 * feature ensures that computed fields automatically get the parent data they need,
 * even when the query doesn't explicitly request those fields.
 */
class SimpleResolversFeatureAppTest : FeatureAppTestBase() {
    override var sdl =
        """
        | #START_SCHEMA
        |
        | interface Person {
        |   firstname: String!
        |   lastname: String!
        | }
        |
        | type User implements Node & Person @resolver {
        |   id: ID!
        |   firstname: String!
        |   lastname: String!
        |   fullName: String! @resolver
        | }
        |
        | extend type Query {
        |   user(id: String!): User! @resolver
        |   person: Person! @resolver
        |   userWithArgs(firstname: String, lastname: String): User! @resolver
        | }
        | #END_SCHEMA
        """.trimMargin()

    /**
     * Node Resolver for User objects. Viaduct generates Nodes.User() base class
     * and calls this resolver when ctx.nodeFor() is used with a User GlobalID.
     * Handles creating/fetching User objects with their basic data.
     */
    class UserNodeResolver : NodeResolvers.User() {
        override suspend fun resolve(ctx: Context): User {
            // Extract internal ID from GlobalID - this would typically be used for DB lookup
            // Static data simulation - in reality this would be a database call
            val userData = when (val internalID = ctx.id.internalID) {
                "john-doe" -> Pair("John", "Doe")
                "jane-doe" -> Pair("Jane", "Doe")
                else -> throw IllegalArgumentException("User not found: $internalID")
            }

            return User.Builder(ctx)
                .id(ctx.id) // Use the GlobalID passed in
                .firstname(userData.first)
                .lastname(userData.second)
                .build()
        }
    }

    /**
     * Query Field Resolver that uses the Node Resolver system. Viaduct generates
     * QueryResolvers.user() and wires this to handle the getUser field.
     * Takes an ID argument and resolves it through the Node Resolver system.
     */
    @Resolver
    class GetUserResolver : QueryResolvers.User() {
        override suspend fun resolve(ctx: Context): User {
            // Get ID from query arguments and create GlobalID for Node Resolver lookup
            return ctx.nodeFor(ctx.globalIDFor(User.Reflection, ctx.arguments.id))
        }
    }

    /**
     * QUERY FIELD RESOLVER - Returns interface type
     *
     * Pattern: Query returns Person interface, actual object is User
     * User implements Person, so it can be returned where Person is expected
     */
    @Resolver
    class GetPersonResolver : QueryResolvers.Person() {
        override suspend fun resolve(ctx: Context): Person {
            // Return a User object that implements Person interface
            return ctx.nodeFor(ctx.globalIDFor(User.Reflection, "john-doe"))
        }
    }

    /**
     * QUERY FIELD RESOLVER - Handles nullable/optional arguments
     *
     * What YOU write:
     * - Check for null arguments with ?: operator
     * - Provide default values when arguments are null
     * - Build object with provided or default data
     *
     * What VIADUCT handles:
     * - Arguments without ! in schema are nullable
     * - Type-safe access via ctx.arguments.firstname (String?)
     */
    @Resolver
    class GetUserWithArgsResolver : QueryResolvers.UserWithArgs() {
        override suspend fun resolve(ctx: Context): User {
            // Handle optional arguments - use defaults if null
            val firstname = ctx.arguments.firstname ?: "DefaultFirst"
            val lastname = ctx.arguments.lastname ?: "DefaultLast"

            // Create a user with provided or default names
            val globalId = ctx.globalIDFor(User.Reflection, "args-user")
            return User.Builder(ctx)
                .id(globalId)
                .firstname(firstname)
                .lastname(lastname)
                .build()
        }
    }

    /**
     * Field Resolver that computes the fullName field on User objects.
     * Viaduct generates UserResolvers.FullName() base class from the schema.
     *
     * objectValueFragment: Tells Viaduct which parent fields this resolver needs.
     * Even if query only asks for "fullName", Viaduct auto-fetches "firstname" and "lastname"
     * from the parent User object so this resolver can access them.
     */
    @Resolver(
        objectValueFragment = "fragment _ on User { firstname lastname }"
    )
    class User_FullNameResolver : UserResolvers.FullName() {
        override suspend fun resolve(ctx: Context): String {
            // Access parent User data via ctx.objectValue (guaranteed by objectValueFragment)
            val firstname = ctx.objectValue.getFirstname()
            val lastname = ctx.objectValue.getLastname()
            return "$firstname $lastname"
        }
    }

    // Test Cases Start here
    @Test
    fun `Query returns user with computed fullName`() {
        execute(
            query = """
                    query TestQuery {
                        user(id: "john-doe") {
                            firstname
                            lastname
                            fullName
                        }
                    }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "user" to {
                    "firstname" to "John"
                    "lastname" to "Doe"
                    "fullName" to "John Doe" // Computed by User_FullNameResolver
                }
            }
        }
    }

    @Test
    fun `Query returns different user data`() {
        execute(
            query = """
                    query TestQuery {
                        user(id: "jane-doe") {
                            firstname
                            lastname
                            fullName
                        }
                    }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "user" to {
                    "firstname" to "Jane"
                    "lastname" to "Doe"
                    "fullName" to "Jane Doe"
                }
            }
        }
    }

    @Test
    fun `Query throws error for unknown user`() {
        // This would result in an error due to our UserNodeResolver validation
        execute(
            query = """
                    query TestQuery {
                        user(id: "unknown-user") {
                            firstname
                        }
                    }
            """.trimIndent()
        ).assertHasError("User not found: unknown-user")
    }

    @Test
    fun `Resolver returns an interface type`() {
        execute(
            query = """
                    query TestQuery {
                        person {
                            firstname
                            lastname
                        }
                    }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "person" to {
                    "firstname" to "John"
                    "lastname" to "Doe"
                }
            }
        }
    }

    @Test
    fun `Resolver with arguments null returns an object type`() {
        execute(
            query = """
                    query TestQuery {
                        userWithArgs(firstname: null, lastname: null) {
                            firstname
                            lastname
                            fullName
                        }
                    }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "userWithArgs" to {
                    "firstname" to "DefaultFirst"
                    "lastname" to "DefaultLast"
                    "fullName" to "DefaultFirst DefaultLast"
                }
            }
        }
    }

    /**
     * EXECUTION FLOW WALKTHROUGH:
     *
     * Query: user(id: "john-doe") { fullName }
     *
     * 1. GetUserResolver.resolve() called with arguments.id = "john-doe"
     * 2. Creates GlobalID: ctx.globalIDFor(User.Reflection, "john-doe")
     * 3. ctx.nodeFor() routes to UserNodeResolver.resolve()
     * 4. UserNodeResolver creates User with firstname="John", lastname="Doe"
     * 5. Viaduct sees fullName requested in query
     * 6. Checks User_FullNameResolver.objectValueFragment
     * 7. Ensures firstname + lastname are available (already fetched in step 4)
     * 8. User_FullNameResolver.resolve() called with populated ctx.objectValue
     * 9. Computes "John Doe" and returns to client
     *
     * KEY TAKEAWAYS:
     * - Node Resolvers handle object creation and basic data
     * - Field Resolvers handle computed/derived fields
     * - objectValueFragment ensures required parent data is available
     * - Viaduct optimizes execution order automatically
     * - Clean separation: creation logic vs computation logic
     */
}
