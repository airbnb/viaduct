@file:Suppress("unused", "ClassName")

package viaduct.tenant.tutorial07

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals as kotlinAssertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.tutorial07.resolverbases.QueryResolvers
import viaduct.tenant.tutorial07.resolverbases.UserResolvers

/**
 * LEARNING OBJECTIVES:
 * - Solve the N+1 query problem for related data
 * - Batch multiple field requests into single operations
 * - Track resolver efficiency with call counting
 * - Understand batchResolve vs regular resolve patterns
 *
 * VIADUCT FEATURES DEMONSTRATED:
 * - Batch Resolvers with batchResolve() method
 * - FieldValue return types for batch results
 * - objectValueFragment for batch context
 * - Automatic batching when multiple objects need same field
 *
 * CONCEPTS COVERED:
 * - N+1 problem: 1 query for users + N queries for each user's department
 * - Batch solution: 1 query for users + 1 query for all departments
 * - DataLoader pattern implementation
 *
 * PREVIOUS: [viaduct.tenant.tutorial06.SimpleScopesFeatureAppTest]
 * NEXT: [viaduct.tenant.tutorial08.BatchNodeResolverFeatureAppTest]
 */
class SimpleBatchResolverFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        | extend type Query {
        |   users: [User!]! @resolver
        |   user(id: String!): User @resolver
        | }
        |
        | type User {
        |   id: String!
        |   name: String!
        |   department: String @resolver    # This will be batch resolved
        | }
        | #END_SCHEMA
    """.trimMargin()

    companion object {
        // PERFORMANCE TRACKING - proves batching efficiency
        val batchResolveCalls = ConcurrentLinkedQueue<Int>()

        fun resetCallTracking() {
            batchResolveCalls.clear()
        }

        fun getTotalBatchResolveCalls(): Int {
            return batchResolveCalls.size
        }
    }

    @BeforeEach
    fun setUp() {
        resetCallTracking()
    }

    /**
     * STANDARD QUERY RESOLVER - Returns list of users
     */
    @Resolver
    class Query_UsersResolver : QueryResolvers.Users() { // Generated from query field
        override suspend fun resolve(ctx: Context): List<User> {
            // TEST DATA - In production: userRepository.findAll()
            return listOf(
                User.Builder(ctx).id("user-1").name("Alice Johnson").build(),
                User.Builder(ctx).id("user-2").name("Bob Smith").build(),
                User.Builder(ctx).id("user-3").name("Carol Williams").build()
            )
        }
    }

    @Resolver
    class Query_UserResolver : QueryResolvers.User() { // Generated from query field
        override suspend fun resolve(ctx: Context): User? {
            val userId = ctx.arguments.id
            // TEST DATA - In production: userRepository.findById(userId)
            return when (userId) {
                "user-1" -> User.Builder(ctx).id("user-1").name("Alice Johnson").build()
                "user-2" -> User.Builder(ctx).id("user-2").name("Bob Smith").build()
                "user-3" -> User.Builder(ctx).id("user-3").name("Carol Williams").build()
                else -> null
            }
        }
    }

    /**
     * BATCH RESOLVER - Solves N+1 problem for department field
     *
     * What YOU write:
     * - Implement batchResolve() instead of resolve()
     * - Extract IDs from all contexts at once
     * - Make single database/service call for all IDs
     * - Return List<FieldValue<T>> matching input order
     *
     * What VIADUCT handles:
     * - Collects all department field requests across multiple User objects
     * - Calls batchResolve() once with all contexts
     * - Maps results back to individual field requests
     * - Handles errors per individual field
     */
    @Resolver(
        objectValueFragment = "fragment _ on User { id }"
    )
    class User_DepartmentResolver : UserResolvers.Department() { // Generated from field with @resolver
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<String>> {
            // EXTRACT ALL USER IDS FROM BATCH
            val userIds = contexts.map { ctx -> ctx.objectValue.getId() }

            // PERFORMANCE TRACKING - record batch size
            batchResolveCalls.add(userIds.size)

            // SINGLE DATABASE CALL - instead of N separate calls
            // In reality: SELECT user_id, department FROM user_departments WHERE user_id IN (?)
            val departmentData = fetchDepartmentsForUsers(userIds)

            // RETURN RESULTS IN SAME ORDER as input contexts
            return contexts.map { ctx ->
                val userId = ctx.objectValue.getId()
                val department = departmentData[userId] ?: "Unknown"
                FieldValue.ofValue(department)
            }
        }

        // SIMULATES SINGLE OPTIMIZED DATABASE QUERY
        private fun fetchDepartmentsForUsers(userIds: List<String>): Map<String, String> {
            return mapOf(
                "user-1" to "Engineering",
                "user-2" to "Marketing",
                "user-3" to "Engineering"
            ).filter { it.key in userIds }
        }
    }

    @Test
    fun `Batch resolver efficiently loads departments for multiple users with single database call`() {
        resetCallTracking()

        execute(
            query = """
                query {
                    users {
                        id
                        name
                        department    # Triggers batchResolve for ALL users at once
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "users" to arrayOf(
                    {
                        "id" to "user-1"
                        "name" to "Alice Johnson"
                        "department" to "Engineering"
                    },
                    {
                        "id" to "user-2"
                        "name" to "Bob Smith"
                        "department" to "Marketing"
                    },
                    {
                        "id" to "user-3"
                        "name" to "Carol Williams"
                        "department" to "Engineering"
                    }
                )
            }
        }

        // EFFICIENCY PROOF - only 1 batch call for all 3 users
        kotlinAssertEquals(1, getTotalBatchResolveCalls(), "Expected exactly 1 batchResolve call")
        kotlinAssertEquals(3, batchResolveCalls.first(), "Expected batch size of 3 users")
    }

    @Test
    fun `Single user query still uses batch resolver with size 1`() {
        resetCallTracking()

        execute(
            query = """
                query {
                    user(id: "user-1") {
                        id
                        name
                        department
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "user" to {
                    "id" to "user-1"
                    "name" to "Alice Johnson"
                    "department" to "Engineering"
                }
            }
        }

        // Even single requests use batch pattern
        kotlinAssertEquals(1, getTotalBatchResolveCalls(), "Expected 1 batchResolve call")
        kotlinAssertEquals(1, batchResolveCalls.first(), "Expected batch size of 1")
    }

    @Test
    fun `Mixed query demonstrates maximum batching efficiency`() {
        resetCallTracking()

        execute(
            query = """
                query {
                    users {                    # 3 users requesting department
                        id
                        name
                        department
                    }
                    singleUser: user(id: "user-2") {  # 1 more user requesting department
                        id
                        name
                        department
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "users" to arrayOf(
                    {
                        "id" to "user-1"
                        "name" to "Alice Johnson"
                        "department" to "Engineering"
                    },
                    {
                        "id" to "user-2"
                        "name" to "Bob Smith"
                        "department" to "Marketing"
                    },
                    {
                        "id" to "user-3"
                        "name" to "Carol Williams"
                        "department" to "Engineering"
                    }
                )
                "singleUser" to {
                    "id" to "user-2"
                    "name" to "Bob Smith"
                    "department" to "Marketing"
                }
            }
        }

        // ALL department requests batched together: 3 + 1 = 4 in single call
        kotlinAssertEquals(1, getTotalBatchResolveCalls(), "Expected 1 batchResolve call")
        kotlinAssertEquals(4, batchResolveCalls.first(), "Expected batch size of 4 (includes duplicate)")
    }

    @Test
    fun `Query without department field does not trigger batch resolver`() {
        resetCallTracking()

        execute(
            query = """
                query {
                    users {
                        id
                        name
                        # department field not requested
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "users" to arrayOf(
                    {
                        "id" to "user-1"
                        "name" to "Alice Johnson"
                    },
                    {
                        "id" to "user-2"
                        "name" to "Bob Smith"
                    },
                    {
                        "id" to "user-3"
                        "name" to "Carol Williams"
                    }
                )
            }
        }

        // No department requests = no batch resolver calls
        kotlinAssertEquals(0, getTotalBatchResolveCalls(), "Expected no batchResolve calls")
    }

    /**
     * PERFORMANCE COMPARISON:
     *
     * Without Batch Resolvers (N+1 Problem):
     * 1. Query: users { department }
     * 2. users resolver: 1 database call
     * 3. department resolver for user-1: 1 database call
     * 4. department resolver for user-2: 1 database call
     * 5. department resolver for user-3: 1 database call
     * Total: 4 database calls (1 + N)
     *
     * With Batch Resolvers (Optimized):
     * 1. Query: users { department }
     * 2. users resolver: 1 database call
     * 3. batchResolve for all departments: 1 database call
     * Total: 2 database calls (1 + 1)
     *
     * KEY TAKEAWAYS:
     * - batchResolve() receives ALL contexts needing the field
     * - Make single optimized call for all required data
     * - Return List<FieldValue<T>> in same order as input
     * - Viaduct handles automatic batching and result mapping
     * - Dramatic performance improvement for N+1 scenarios
     */
}
