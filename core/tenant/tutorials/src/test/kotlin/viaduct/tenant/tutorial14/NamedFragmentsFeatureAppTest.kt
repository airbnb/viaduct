@file:Suppress("unused", "ClassName")
@file:OptIn(ExperimentalApi::class)

package viaduct.tenant.tutorial14

import org.junit.jupiter.api.Test
import viaduct.api.documents.FragmentFromAnnotation
import viaduct.api.documents.GraphQLFragment
import viaduct.api.resolver.Resolver
import viaduct.apiannotations.ExperimentalApi
import viaduct.graphql.test.assertEquals
import viaduct.tenant.tutorial14.resolverbases.QueryResolvers
import viaduct.tenant.tutorial14.resolverbases.UserResolvers

/**
 * LEARNING OBJECTIVES:
 * - Define a reusable GraphQL fragment in Kotlin with @GraphQLFragment
 * - Spread a named fragment (`...FragmentName`) inside a resolver's objectValueFragment
 * - Nest a named fragment inside another named fragment and spread the outer one
 *
 * VIADUCT FEATURES DEMONSTRATED:
 * - @GraphQLFragment — declares a named fragment on a Kotlin singleton object
 * - FragmentFromAnnotation<T> — the base class a fragment object extends (T = the type it selects on)
 * - Spreading `...FragmentName` inside @Resolver(objectValueFragment = ...)
 * - Fragment composition: a fragment that spreads another fragment
 *
 * CONCEPTS COVERED:
 * - A named fragment is defined ONCE and reused across many resolvers — no copy/pasting selections
 * - Fragments are discovered at bootstrap time by scanning for @GraphQLFragment objects
 * - Named fragments are scoped to the tenant module in which they are declared
 *   (spreading a fragment from another module is a compile error)
 * - Fragments compose: UserProfile spreads UserIdentity, so requesting UserProfile
 *   pulls in every field UserIdentity selects, plus its own
 *
 * PREVIOUS: [viaduct.tenant.tutorial13.RootFieldRefFeatureAppTest]
 * NEXT: [viaduct.tenant.tutorial15.GraphQLOperationsFeatureAppTest]
 *
 * ## Schema
 * ```graphql
 * extend type Query {
 *   user(id: ID!): User @resolver
 * }
 *
 * type User {
 *   id: ID!
 *   name: String!
 *   email: String!
 *   card: String! @resolver       # uses the UserIdentity fragment
 *   profile: String! @resolver    # uses UserProfile, which nests UserIdentity
 * }
 * ```
 */
class NamedFragmentsFeatureAppTest : NamedFragmentsContractTest() {
    companion object {
        // ======================= Named fragment definitions =======================

        /**
         * A SIMPLE NAMED FRAGMENT.
         *
         * What YOU write:
         * - A Kotlin `object` extending FragmentFromAnnotation<T>, where T is the GraphQL type the
         *   fragment selects on (here, User).
         * - The @GraphQLFragment annotation carrying exactly one `fragment ... on ... { ... }` block.
         *
         * What VIADUCT does:
         * - Discovers this object at bootstrap by scanning for @GraphQLFragment.
         * - Makes the fragment name (UserIdentity) available to spread as `...UserIdentity`
         *   anywhere in this tenant module.
         *
         * Reuse: any resolver that needs a user's id + name can spread `...UserIdentity` instead of
         * repeating `id name`.
         */
        @GraphQLFragment("fragment UserIdentity on User { id name }")
        object UserIdentityFragment : FragmentFromAnnotation<User>()

        /**
         * A NESTED NAMED FRAGMENT — a fragment that spreads another fragment.
         *
         * UserProfile spreads `...UserIdentity` and then adds `email`. Requesting UserProfile
         * therefore selects id, name (from UserIdentity) AND email. This is fragment composition:
         * build small fragments and combine them, rather than one large duplicated selection.
         */
        @GraphQLFragment("fragment UserProfile on User { ...UserIdentity email }")
        object UserProfileFragment : FragmentFromAnnotation<User>()
    }

    // ======================= Query root resolver =======================

    @Resolver
    class Query_UserResolver : QueryResolvers.User() {
        override suspend fun resolve(ctx: Context): User {
            val id = ctx.arguments.id
            return User.Builder(ctx)
                .id(id)
                .name("User-$id")
                .email("$id@example.com")
                .build()
        }
    }

    // ======================= User field resolvers =======================

    /**
     * SPREADING A SIMPLE NAMED FRAGMENT.
     *
     * The objectValueFragment spreads `...UserIdentity`. At execution time the engine has already
     * fetched id and name (because the fragment declared them), so ctx.getObjectValue() can read
     * both. Note the wrapper fragment name (`Main`) is arbitrary — only the spread matters.
     */
    @Resolver(objectValueFragment = "fragment Main on User { ...UserIdentity }")
    class User_CardResolver : UserResolvers.Card() {
        override suspend fun resolve(ctx: Context): String {
            val user = ctx.getObjectValue()
            return "${user.getName()} (${user.getId()})"
        }
    }

    /**
     * SPREADING A NESTED NAMED FRAGMENT.
     *
     * The objectValueFragment spreads `...UserProfile`, which itself spreads `...UserIdentity`.
     * So id, name, AND email are all available here — the nesting is resolved transitively by the
     * engine. This resolver never repeats those field names; it just reuses the composed fragment.
     */
    @Resolver(objectValueFragment = "fragment Main on User { ...UserProfile }")
    class User_ProfileResolver : UserResolvers.Profile() {
        override suspend fun resolve(ctx: Context): String {
            val user = ctx.getObjectValue()
            return "${user.getName()} <${user.getEmail()}> [${user.getId()}]"
        }
    }

    // ======================= Tests =======================

    @Test
    fun `simple named fragment provides fields to the resolver`() {
        execute(
            query = """
                query {
                    user(id: "alice") {
                        card
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "user" to {
                    "card" to "User-alice (alice)"
                }
            }
        }
    }

    @Test
    fun `nested named fragment composes UserIdentity with email`() {
        execute(
            query = """
                query {
                    user(id: "bob") {
                        profile
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "user" to {
                    "profile" to "User-bob <bob@example.com> [bob]"
                }
            }
        }
    }
}
