@file:Suppress("unused", "ClassName")

package viaduct.tenant.tutorial10

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.Variable
import viaduct.api.Variables
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.types.Arguments
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.tutorial10.resolverbases.QueryResolvers

/**
 * LEARNING OBJECTIVES:
 * - Control GraphQL field arguments dynamically using variables
 * - Master 3 patterns: declarative, VariablesProvider, and argument-based
 * - Use variables to inject arguments into required selection sets
 * - Implement conditional argument passing based on business logic
 *
 * VIADUCT FEATURES DEMONSTRATED:
 * - Variable declarations with @Variable annotation
 * - VariablesProvider for computed variable values
 * - objectValueFragment with variable arguments
 * - fromArgument variable population
 *
 * CONCEPTS COVERED:
 * - Dynamic argument injection into selection sets
 * - Runtime argument computation
 * - Variable scope and computation patterns
 * - Conditional field argument control
 *
 * PREVIOUS: [viaduct.tenant.tutorial09.VariablesDirectivesFeatureAppTest]
 * NEXT: End of tutorial series
 */
class VariablesForArgumentsFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        | extend type Query {
        |   getPosts(userId: String!, status: String!): String @resolver
        |   userPosts(userId: String!): String @resolver
        |   latestPosts: String @resolver
        |   dashboardPosts(userType: String!): String @resolver
        | }
        | #END_SCHEMA
    """.trimMargin()

    /**
     * BASE RESOLVER - The target resolver that receives arguments
     *
     * This resolver demonstrates what arguments look like when they come
     * from different variable patterns. Other resolvers will call this
     * one through their selection sets with computed arguments.
     */
    @Resolver
    class QueryGetPostsResolver : QueryResolvers.GetPosts() { // Generated from query field
        override suspend fun resolve(ctx: Context): String {
            val userId = ctx.arguments.userId
            val status = ctx.arguments.status

            // YOUR BUSINESS LOGIC - typically database query
            // In production: postsService.getPosts(userId, status)
            return "Posts for $userId with status $status"
        }
    }

    /**
     * PATTERN 1: DECLARATIVE VARIABLES - No code required
     *
     * What YOU write:
     * - variables = [Variable("name", fromArgument = "argumentName")]
     * - Use variable in objectValueFragment arguments
     *
     * What VIADUCT handles:
     * - Automatically extracts argument value into variable
     * - Injects variable value into selection set arguments
     * - No VariablesProvider class needed
     */
    @Resolver(
        """
        fragment _ on Query {
            getPosts(userId: ${'$'}userIdVar, status: "published")
        }
        """,
        variables = [Variable("userIdVar", fromArgument = "userId")]
    )
    class QueryUserPostsResolver : QueryResolvers.UserPosts() { // Generated from query field
        override suspend fun resolve(ctx: Context): String = ctx.objectValue.get("getPosts", String::class)
    }

    @Test
    fun `Pattern 1 - Using declarative variables to inject arguments`() {
        execute("{ userPosts(userId: \"john\") }").assertEquals {
            "data" to {
                "userPosts" to "Posts for john with status published"
            }
        }
    }

    /**
     * PATTERN 2: VARIABLESPROVIDER WITHOUT ARGUMENTS
     *
     * What YOU write:
     * - VariablesProvider class implementing provide() method
     * - @Variables annotation declaring variable types
     * - Business logic to compute variable values
     *
     * What VIADUCT handles:
     * - Calls VariablesProvider.provide() at runtime
     * - Uses returned values as arguments in selection set
     * - No access to resolver arguments (Arguments.NoArguments)
     */
    @Resolver(
        """
        fragment _ on Query {
            getPosts(userId: ${'$'}currentUser, status: "published")
        }
        """
    )
    class QueryLatestPostsResolver : QueryResolvers.LatestPosts() { // Generated from query field
        override suspend fun resolve(ctx: Context): String = ctx.objectValue.get("getPosts", String::class)

        @Variables("currentUser: String!")
        class LatestPostsProvider : VariablesProvider<Arguments.NoArguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments.NoArguments>): Map<String, Any?> {
                // COMPUTED ARGUMENTS - typically from auth context, config, etc.
                // In production: context.authContext.getCurrentUser()
                val currentUser = "user"

                return mapOf(
                    "currentUser" to currentUser,
                )
            }
        }
    }

    @Test
    fun `Pattern 2 - Using VariablesProvider without arguments to compute arguments`() {
        execute("{ latestPosts }").assertEquals {
            "data" to {
                "latestPosts" to "Posts for user with status published"
            }
        }
    }

    /**
     * PATTERN 3: VARIABLESPROVIDER WITH RESOLVER ARGUMENTS
     *
     * What YOU write:
     * - VariablesProvider with typed arguments (Query_Field_Arguments)
     * - Access resolver arguments via context.args
     * - Conditional logic based on input arguments
     *
     * What VIADUCT handles:
     * - Provides resolver arguments to VariablesProvider
     * - Enables dynamic argument computation based on inputs
     * - Most powerful pattern for conditional behavior
     */
    @Resolver(
        """
        fragment _ on Query {
            getPosts(userId: ${'$'}targetUser, status: ${'$'}statusFilter)
        }
        """
    )
    class QueryDashboardPostsResolver : QueryResolvers.DashboardPosts() { // Generated from query field
        override suspend fun resolve(ctx: Context): String = ctx.objectValue.get("getPosts", String::class)

        @Variables("targetUser: String!, statusFilter: String!")
        class DashboardProvider : VariablesProvider<Query_DashboardPosts_Arguments> { // Generated arguments type
            override suspend fun provide(context: VariablesProviderContext<Query_DashboardPosts_Arguments>): Map<String, Any?> {
                // ACCESS RESOLVER ARGUMENTS
                val userType = context.args.userType

                // CONDITIONAL ARGUMENT COMPUTATION
                return when (userType) {
                    "admin" -> mapOf(
                        "targetUser" to "admin",
                        "statusFilter" to "all"
                    )
                    else -> mapOf(
                        "targetUser" to "guest",
                        "statusFilter" to "published"
                    )
                }
            }
        }
    }

    @Test
    fun `Pattern 3 - Using VariablesProvider with arguments for conditional logic`() {
        execute("{ dashboardPosts(userType: \"admin\") }").assertEquals {
            "data" to {
                "dashboardPosts" to "Posts for admin with status all"
            }
        }

        execute("{ dashboardPosts(userType: \"guest\") }").assertEquals {
            "data" to {
                "dashboardPosts" to "Posts for guest with status published"
            }
        }
    }

    /**
     * EXECUTION FLOW WALKTHROUGH:
     *
     * Pattern 1: userPosts(userId: "john")
     * 1. QueryUserPostsResolver called with userId="john"
     * 2. Variable userIdVar populated from userId argument
     * 3. Selection set: getPosts(userId: "john", status: "published")
     * 4. QueryGetPostsResolver called with computed arguments
     * 5. Returns "Posts for john with status published"
     *
     * Pattern 3: dashboardPosts(userType: "admin")
     * 1. QueryDashboardPostsResolver called with userType="admin"
     * 2. DashboardProvider.provide() called with access to arguments
     * 3. Computes: targetUser="admin", statusFilter="all"
     * 4. Selection set: getPosts(userId: "admin", status: "all")
     * 5. QueryGetPostsResolver called with computed arguments
     * 6. Returns "Posts for admin with status all"
     *
     * KEY TAKEAWAYS:
     * - Variables can control selection set arguments, not just directives
     * - Pattern 1: Simple argument forwarding
     * - Pattern 2: Computed arguments without input context
     * - Pattern 3: Conditional arguments based on resolver inputs
     * - Enables complex argument transformation and business logic
     */
}
