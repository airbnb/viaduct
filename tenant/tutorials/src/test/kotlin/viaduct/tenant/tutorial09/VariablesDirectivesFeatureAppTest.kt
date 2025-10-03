@file:Suppress("unused", "ClassName")

package viaduct.tenant.tutorial09

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.Variable
import viaduct.api.Variables
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.types.Arguments
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.tutorial09.resolverbases.QueryResolvers
import viaduct.tenant.tutorial09.resolverbases.UserResolvers

/**
 * LEARNING OBJECTIVES:
 * - Control GraphQL directives (@include/@skip) dynamically
 * - Use variables to conditionally fetch fields at runtime
 * - Master 3 patterns: declarative, VariablesProvider, and argument-based
 * - Implement conditional data fetching based on business logic
 *
 * VIADUCT FEATURES DEMONSTRATED:
 * - Variable declarations with @Variable annotation
 * - VariablesProvider for computed variable values
 * - objectValueFragment with directive variables
 * - fromArgument variable population
 *
 * CONCEPTS COVERED:
 * - GraphQL directive system (@include/@skip)
 * - Runtime field selection optimization
 * - Conditional data access patterns
 * - Variable scope and computation
 *
 * PREVIOUS: [viaduct.tenant.tutorial08.BatchNodeResolverFeatureAppTest]
 * NEXT: [viaduct.tenant.tutorial10.VariablesForArgumentsFeatureAppTest]
 */
class VariablesDirectivesFeatureAppTest : FeatureAppTestBase() {
    companion object {
        // TEST DATA
        data class UserModel(val id: String, val name: String)

        val USER1 = UserModel("user-123", "John Doe")
        val USER2 = UserModel("user-456", "Jane Smith")
        val REVIEWS_USER_1_ANONYMOUS = listOf("Bad!", "Won't buy again")
        val REVIEWS_USER_1_VERIFIED = listOf("Great product!", "Loved the service", "Will buy again")
        val REVIEWS_USER_2_ANONYMOUS = listOf("Bad Quality", "Fast delivery")
        val REVIEWS_USER_2_VERIFIED = listOf("Defective Product", "Fast delivery")
    }

    override var sdl = """
        | #START_SCHEMA
        | type User implements Node @resolver {
        |   id: ID!
        |   name: String!
        |   anonymousReviews: [String!]! @resolver
        |   verifiedReviews: [String!]! @resolver
        |   reviews(anonymous: Boolean!): [String!]! @resolver
        |   computedReviews: [String!]! @resolver
        |   computedReviewsWithArgs(userType: String!): [String!]! @resolver
        | }
        |
        | extend type Query {
        |   user(id: String!): User! @resolver
        | }
        | #END_SCHEMA
    """.trimMargin()

    class UserNodeResolver : NodeResolvers.User() {
        override suspend fun resolve(ctx: Context): User {
            val userData = when (val internalID = ctx.id.internalID) {
                USER1.id -> USER1.name
                USER2.id -> USER2.name
                else -> throw IllegalArgumentException("User not found: $internalID")
            }

            return User.Builder(ctx)
                .id(ctx.id)
                .name(userData)
                .build()
        }
    }

    @Resolver
    class QueryUserResolver : QueryResolvers.User() {
        override suspend fun resolve(ctx: Context): User {
            return ctx.nodeFor(ctx.globalIDFor(User.Reflection, ctx.arguments.id))
        }
    }

    @Resolver("id")
    class UserAnonymousReviews : UserResolvers.AnonymousReviews() {
        override suspend fun resolve(ctx: Context): List<String> {
            return when (ctx.objectValue.getId().internalID) {
                USER1.id -> REVIEWS_USER_1_ANONYMOUS
                USER2.id -> REVIEWS_USER_2_ANONYMOUS
                else -> emptyList()
            }
        }
    }

    @Resolver("id")
    class UserVerifiedReviews : UserResolvers.VerifiedReviews() {
        override suspend fun resolve(ctx: Context): List<String> {
            return when (ctx.objectValue.getId().internalID) {
                USER1.id -> REVIEWS_USER_1_VERIFIED
                USER2.id -> REVIEWS_USER_2_VERIFIED
                else -> emptyList()
            }
        }
    }

    /**
     * PATTERN 1: DECLARATIVE VARIABLES - No code required
     *
     * What YOU write:
     * - variables = [Variable("name", fromArgument = "argumentName")]
     * - Use variable in objectValueFragment with @include/@skip
     *
     * What VIADUCT handles:
     * - Automatically extracts argument value into variable
     * - Evaluates directive at query time
     * - Only fetches fields when directive condition is true
     */
    @Resolver(
        """
        fragment _ on User {
            anonymousReviews @include(if: ${'$'}anonymousVar)
            verifiedReviews
        }
        """,
        variables = [Variable("anonymousVar", fromArgument = "anonymous")]
    )
    class UserReviewsResolver : UserResolvers.Reviews() {
        override suspend fun resolve(ctx: Context): List<String> {
            return if (ctx.arguments.anonymous) {
                // anonymousReviews available due to @include(if: true)
                ctx.objectValue.getAnonymousReviews() + ctx.objectValue.getVerifiedReviews()
            } else {
                // anonymousReviews not fetched due to @include(if: false)
                ctx.objectValue.getVerifiedReviews()
            }
        }
    }

    @Test
    fun `Pattern 1 - Declarative variables from arguments control field fetching`() {
        // anonymous = true -> includes anonymousReviews field
        execute("{ user(id: \"${USER1.id}\") { name reviews(anonymous: true) } }").assertEquals {
            "data" to {
                "user" to {
                    "name" to USER1.name
                    "reviews" to REVIEWS_USER_1_ANONYMOUS + REVIEWS_USER_1_VERIFIED
                }
            }
        }

        // anonymous = false -> skips anonymousReviews field (performance optimization)
        execute("{ user(id: \"${USER1.id}\") { name reviews(anonymous: false) } }").assertEquals {
            "data" to {
                "user" to {
                    "name" to USER1.name
                    "reviews" to REVIEWS_USER_1_VERIFIED
                }
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
     * - Uses returned values to evaluate directives
     * - Optimizes field fetching based on computed variables
     */
    @Resolver(
        """
        fragment _ on User {
            anonymousReviews @include(if: ${'$'}anonymousVar)
            verifiedReviews
        }
        """
    )
    class UserComputedReviews : UserResolvers.ComputedReviews() {
        override suspend fun resolve(ctx: Context): List<String> {
            return try {
                // If anonymousVar = true, anonymousReviews will be available
                ctx.objectValue.getAnonymousReviews() + ctx.objectValue.getVerifiedReviews()
            } catch (ex: Exception) {
                // If anonymousVar = false, anonymousReviews won't be fetched
                ctx.objectValue.getVerifiedReviews()
            }
        }

        @Variables("anonymousVar: Boolean")
        class Vars : VariablesProvider<Arguments.NoArguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments.NoArguments>): Map<String, Any> {
                // BUSINESS LOGIC - could be based on user permissions, feature flags, etc.
                return mapOf("anonymousVar" to false)
            }
        }
    }

    @Test
    fun `Pattern 2 - VariablesProvider computes directive values at runtime`() {
        execute("{ user(id: \"${USER2.id}\") { name computedReviews } }").assertEquals {
            "data" to {
                "user" to {
                    "name" to USER2.name
                    "computedReviews" to REVIEWS_USER_2_VERIFIED // anonymousVar = false
                }
            }
        }
    }

    /**
     * PATTERN 3: VARIABLESPROVIDER WITH RESOLVER ARGUMENTS
     *
     * Most powerful pattern - access to resolver arguments for conditional logic
     */
    @Resolver(
        """
        fragment _ on User {
            anonymousReviews @skip(if: ${'$'}skipAnonymous)
            verifiedReviews
        }
        """
    )
    class UserComputedReviewsWithArgs : UserResolvers.ComputedReviewsWithArgs() {
        override suspend fun resolve(ctx: Context): List<String> {
            return try {
                ctx.objectValue.getAnonymousReviews() + ctx.objectValue.getVerifiedReviews()
            } catch (ex: Exception) {
                ctx.objectValue.getVerifiedReviews()
            }
        }

        @Variables("skipAnonymous: Boolean")
        class Vars : VariablesProvider<User_ComputedReviewsWithArgs_Arguments> {
            override suspend fun provide(context: VariablesProviderContext<User_ComputedReviewsWithArgs_Arguments>): Map<String, Any> {
                val shouldSkipAnonymous = when (context.args.userType) {
                    "verified" -> true
                    else -> false
                }
                return mapOf("skipAnonymous" to shouldSkipAnonymous)
            }
        }
    }

    @Test
    fun `Pattern 3 - VariablesProvider with resolver arguments for conditional control`() {
        execute("{ user(id: \"${USER1.id}\") { name computedReviewsWithArgs(userType: \"verified\") } }").assertEquals {
            "data" to {
                "user" to {
                    "name" to USER1.name
                    "computedReviewsWithArgs" to REVIEWS_USER_1_VERIFIED
                }
            }
        }

        execute("{ user(id: \"${USER2.id}\") { name computedReviewsWithArgs(userType: \"guest\") } }").assertEquals {
            "data" to {
                "user" to {
                    "name" to USER2.name
                    "computedReviewsWithArgs" to REVIEWS_USER_2_ANONYMOUS + REVIEWS_USER_2_VERIFIED
                }
            }
        }
    }

    /**
     * EXECUTION FLOW WALKTHROUGH:
     *
     * Pattern 1: reviews(anonymous: true)
     * 1. Variable anonymousVar set to true from argument
     * 2. @include(if: true) -> anonymousReviews field is fetched
     * 3. UserAnonymousReviews resolver runs
     * 4. UserReviewsResolver gets both anonymous + verified reviews
     *
     * Pattern 3: computedReviewsWithArgs(userType: "verified")
     * 1. VariablesProvider receives userType="verified"
     * 2. Computes skipAnonymous=true
     * 3. @skip(if: true) -> anonymousReviews field is NOT fetched
     * 4. Only UserVerifiedReviews resolver runs
     * 5. Result contains only verified reviews
     *
     * KEY TAKEAWAYS:
     * - Variables control @include/@skip directives dynamically
     * - Pattern 1: Simple argument-based control
     * - Pattern 2: Computed variables without input context
     * - Pattern 3: Conditional variables based on resolver arguments
     * - Performance optimization through selective field fetching
     */
}
