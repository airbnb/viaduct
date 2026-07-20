@file:Suppress("unused", "ClassName")
@file:OptIn(ExperimentalApi::class)

package viaduct.tenant.tutorial15

import org.junit.jupiter.api.Test
import viaduct.api.documents.FragmentFromAnnotation
import viaduct.api.documents.GraphQLFragment
import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.MutationFromAnnotation
import viaduct.api.documents.QueryFromAnnotation
import viaduct.api.resolver.Resolver
import viaduct.apiannotations.ExperimentalApi
import viaduct.graphql.test.assertEquals
import viaduct.tenant.tutorial15.resolverbases.MutationResolvers
import viaduct.tenant.tutorial15.resolverbases.QueryResolvers
import viaduct.tenant.tutorial15.resolverbases.RunnerResolvers

/**
 * LEARNING OBJECTIVES:
 * - Declare a reusable GraphQL operation in Kotlin with @GraphQLOperation
 * - Run a query operation with NO variables via ctx.query(operation)
 * - Run a query operation WITH variables via ctx.query(operation, variables)
 * - Run a query operation that spreads a named fragment (@GraphQLFragment)
 * - Run a mutation operation with variables via ctx.mutation(operation, variables)
 *
 * VIADUCT FEATURES DEMONSTRATED:
 * - @GraphQLOperation — declares a whole executable operation on a Kotlin singleton object
 * - QueryFromAnnotation / MutationFromAnnotation — the base classes an operation object extends
 * - ctx.query(operation) / ctx.query(operation, variables) — execute a declared query operation
 * - ctx.mutation(operation, variables) — execute a declared mutation operation
 * - Operations spreading a @GraphQLFragment (`...FragmentName`)
 *
 * CONCEPTS COVERED:
 * - An operation is declared ONCE as an object, then executed by passing the object itself —
 *   no re-parsing an inline string at every call site (contrast ctx.query("...") in Tutorial 11)
 * - The operation document is validated against the schema at compile time
 * - Operations may declare variables ($value) and receive them via the variables map
 * - Operations may reuse named fragments from the same module (see Tutorial 14 for fragments)
 * - ctx.mutation(operation) is only available in mutation resolver contexts (compile-time enforced)
 *
 * PREVIOUS: [viaduct.tenant.tutorial14.NamedFragmentsFeatureAppTest]
 *
 * ## Schema
 * ```graphql
 * extend type Query {
 *   greeting: String! @resolver
 *   echo(value: String!): String! @resolver
 *   user(id: ID!): User @resolver
 *   runner: Runner @resolver
 * }
 *
 * type User { id: ID!  name: String! }
 *
 * type Runner {
 *   fetchGreeting: String! @resolver          # ctx.query(operation), no variables
 *   fetchEcho(value: String!): String! @resolver  # ctx.query(operation, variables)
 *   fetchUserCard(id: ID!): String! @resolver      # operation that spreads a fragment
 * }
 *
 * extend type Mutation {
 *   save(value: String!): String! @resolver
 *   runSave(value: String!): String! @resolver     # ctx.mutation(operation, variables)
 * }
 * ```
 */
class GraphQLOperationsFeatureAppTest : GraphQLOperationsContractTest() {
    companion object {
        // ======================= Operation & fragment declarations =======================

        /**
         * A QUERY OPERATION WITH NO VARIABLES.
         *
         * What YOU write:
         * - A Kotlin `object` extending QueryFromAnnotation.
         * - @GraphQLOperation carrying exactly one operation document. Here it selects a single
         *   root field with no arguments and no variables.
         *
         * What VIADUCT does:
         * - Discovers the operation at bootstrap and validates the document against the schema.
         * - Lets you run it later by passing the object itself to ctx.query(GreetingQuery).
         */
        @GraphQLOperation("{ greeting }")
        object GreetingQuery : QueryFromAnnotation()

        /**
         * A QUERY OPERATION WITH A VARIABLE.
         *
         * The document declares a `$value: String!` variable and forwards it to the echo field.
         * At the call site you supply the value via the variables map. Declaring the variable in
         * the operation (rather than baking a literal into the string) is what makes the operation
         * reusable across different inputs.
         */
        @GraphQLOperation("query(\$value: String!) { echo(value: \$value) }")
        object EchoQuery : QueryFromAnnotation()

        /**
         * A NAMED FRAGMENT reused by the operation below.
         *
         * Same mechanism as Tutorial 14: a fragment declared once and spread by name.
         */
        @GraphQLFragment("fragment UserCard on User { id name }")
        object UserCardFragment : FragmentFromAnnotation<User>()

        /**
         * A QUERY OPERATION THAT SPREADS A NAMED FRAGMENT.
         *
         * The operation declares an `$id` variable, fetches the user, and spreads `...UserCard`
         * instead of listing `id name` inline. This is how operations and fragments compose:
         * the fragment owns the field selection, the operation owns the shape and variables.
         */
        @GraphQLOperation("query(\$id: ID!) { user(id: \$id) { ...UserCard } }")
        object UserCardQuery : QueryFromAnnotation()

        /**
         * A MUTATION OPERATION WITH A VARIABLE.
         *
         * Extends MutationFromAnnotation (not QueryFromAnnotation) so it can be passed to
         * ctx.mutation(...). Mutation operations are declared and validated the same way as queries.
         */
        @GraphQLOperation("mutation(\$value: String!) { save(value: \$value) }")
        object SaveMutation : MutationFromAnnotation()
    }

    // ======================= Query root resolvers (operation targets) =======================

    @Resolver
    class Query_GreetingResolver : QueryResolvers.Greeting() {
        override suspend fun resolve(ctx: Context): String = "Hello, World!"
    }

    @Resolver
    class Query_EchoResolver : QueryResolvers.Echo() {
        override suspend fun resolve(ctx: Context): String = "echo:${ctx.arguments.value}"
    }

    @Resolver
    class Query_UserResolver : QueryResolvers.User() {
        override suspend fun resolve(ctx: Context): User {
            val id = ctx.arguments.id
            return User.Builder(ctx).id(id).name("User-$id").build()
        }
    }

    @Resolver
    class Query_RunnerResolver : QueryResolvers.Runner() {
        override suspend fun resolve(ctx: Context): Runner = Runner.Builder(ctx).build()
    }

    // ======================= Runner field resolvers (run the query operations) ===============

    /**
     * RUNNING A NO-VARIABLE QUERY OPERATION.
     *
     * Pass the operation object to ctx.query(). The engine executes the declared document against
     * the Query root and returns a typed result with generated getters.
     */
    @Resolver
    class Runner_FetchGreetingResolver : RunnerResolvers.FetchGreeting() {
        override suspend fun resolve(ctx: Context): String {
            val result = ctx.query(GreetingQuery)
            return result.getGreeting()
        }
    }

    /**
     * RUNNING A QUERY OPERATION WITH VARIABLES.
     *
     * The operation declared `$value`; supply it here through the variables map. The map keys must
     * match the variable names declared in the operation document.
     */
    @Resolver
    class Runner_FetchEchoResolver : RunnerResolvers.FetchEcho() {
        override suspend fun resolve(ctx: Context): String {
            val result = ctx.query(EchoQuery, mapOf("value" to ctx.arguments.value))
            return result.getEcho()
        }
    }

    /**
     * RUNNING A QUERY OPERATION THAT SPREADS A FRAGMENT.
     *
     * Nothing special at the call site — the fragment is resolved as part of the operation. The
     * result exposes the fields the fragment selected (id, name).
     */
    @Resolver
    class Runner_FetchUserCardResolver : RunnerResolvers.FetchUserCard() {
        override suspend fun resolve(ctx: Context): String {
            val result = ctx.query(UserCardQuery, mapOf("id" to ctx.arguments.id))
            val user = result.getUser() ?: error("user was null")
            return "${user.getName()} (${user.getId()})"
        }
    }

    // ======================= Mutation resolvers =======================

    @Resolver
    class Mutation_SaveResolver : MutationResolvers.Save() {
        override suspend fun resolve(ctx: Context): String = "saved:${ctx.arguments.value}"
    }

    /**
     * RUNNING A MUTATION OPERATION.
     *
     * ctx.mutation(operation) executes the declared mutation against the Mutation root. It is only
     * callable from a mutation resolver context — the type system prevents calling it from a query
     * resolver at compile time.
     */
    @Resolver
    class Mutation_RunSaveResolver : MutationResolvers.RunSave() {
        override suspend fun resolve(ctx: Context): String {
            val result = ctx.mutation(SaveMutation, mapOf("value" to ctx.arguments.value))
            return result.getSave()
        }
    }

    // ======================= Tests =======================

    @Test
    fun `query operation without variables runs via ctx query`() {
        execute(
            query = """
                query {
                    runner {
                        fetchGreeting
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "runner" to {
                    "fetchGreeting" to "Hello, World!"
                }
            }
        }
    }

    @Test
    fun `query operation with a variable runs via ctx query`() {
        execute(
            query = """
                query {
                    runner {
                        fetchEcho(value: "ping")
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "runner" to {
                    "fetchEcho" to "echo:ping"
                }
            }
        }
    }

    @Test
    fun `query operation that spreads a named fragment runs via ctx query`() {
        execute(
            query = """
                query {
                    runner {
                        fetchUserCard(id: "carol")
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "runner" to {
                    "fetchUserCard" to "User-carol (carol)"
                }
            }
        }
    }

    @Test
    fun `mutation operation runs via ctx mutation`() {
        execute(
            query = """
                mutation {
                    runSave(value: "doc")
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "runSave" to "saved:doc"
            }
        }
    }
}
