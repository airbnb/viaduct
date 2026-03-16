@file:Suppress("unused", "ClassName")

package viaduct.tenant.tutorial11

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.tutorial11.resolverbases.EnrichedGreetingResolvers
import viaduct.tenant.tutorial11.resolverbases.MutationResolvers
import viaduct.tenant.tutorial11.resolverbases.QueryResolvers

/**
 * LEARNING OBJECTIVES:
 * - Execute subqueries against the Query root from any resolver using ctx.query()
 * - Execute submutations from mutation resolvers using ctx.mutation()
 * - Understand when to use ctx.query() vs declarative @Resolver fragments
 * - Pass variables to subqueries
 *
 * VIADUCT FEATURES DEMONSTRATED:
 * - ctx.query() — imperative subquery against the Query root
 * - ctx.mutation() — submutation from a mutation resolver
 * - ctx.query(selections, variables) — subquery with a variables map
 * - Comparison with queryValueFragment in @Resolver
 *
 * CONCEPTS COVERED:
 * - Subquery execution reuses the parent request's execution context (no new HTTP round-trip)
 * - Subquery variables are scoped to the subquery and don't inherit from the parent request
 * - ctx.mutation() is only available in mutation resolver contexts (compile-time enforcement)
 * - Mutation resolvers can also call ctx.query() freely
 *
 * PREVIOUS: [viaduct.tenant.tutorial10.VariablesForArgumentsFeatureAppTest]
 * NEXT: [viaduct.tenant.tutorial12.ConnectionsFeatureAppTest]
 */
class SimpleSubqueriesFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        | extend type Query {
        |   greeting: String @resolver          # Returns a static greeting string
        |   multiply(n: Int!): Int @resolver    # Returns n * 2
        |   enriched: EnrichedGreeting @resolver
        | }
        |
        | type EnrichedGreeting {
        |   # ctx.query() — fetches data from the Query root at runtime
        |   message: String @resolver
        |   # ctx.query(selections, variables) — passes a computed value as a subquery variable
        |   doubled(input: Int!): Int @resolver
        | }
        |
        | extend type Mutation {
        |   step: Int @resolver              # Increments and returns counter
        |   pipeline: String @resolver       # Uses ctx.mutation() + ctx.query() together
        | }
        | #END_SCHEMA
    """.trimMargin()

    companion object {
        var counter = 0
    }

    // ======================= Query root resolvers =======================

    @Resolver
    class GreetingResolver : QueryResolvers.Greeting() {
        override suspend fun resolve(ctx: Context): String = "Hello, World!"
    }

    @Resolver
    class MultiplyResolver : QueryResolvers.Multiply() {
        override suspend fun resolve(ctx: Context): Int = ctx.arguments.n * 2
    }

    @Resolver
    class EnrichedResolver : QueryResolvers.Enriched() {
        override suspend fun resolve(ctx: Context): EnrichedGreeting = EnrichedGreeting.Builder(ctx).build()
    }

    // ======================= EnrichedGreeting field resolvers =======================

    /**
     * USING ctx.query() — imperative subquery
     *
     * What YOU write:
     * - Call ctx.query(selectionString) with standard GraphQL selection syntax
     * - Access results via typed getter methods on the returned object
     *
     * What VIADUCT does:
     * - Executes the selection against the Query root
     * - Reuses the current request's execution context (no new HTTP round-trip)
     * - Returns a typed object with generated accessor methods
     *
     * When to use ctx.query() vs @Resolver fragments:
     * - Use queryValueFragment in @Resolver when fields are known at registration time
     *   (more efficient: the engine plans and batches them at query planning time)
     * - Use ctx.query() when which fields to fetch depends on runtime logic
     */
    @Resolver
    class MessageResolver : EnrichedGreetingResolvers.Message() {
        override suspend fun resolve(ctx: Context): String {
            // SUBQUERY: fetch "greeting" from the Query root at runtime
            val queryResult = ctx.query("greeting")
            val greeting = queryResult.getGreeting() ?: "Hello"
            return "$greeting (enriched)"
        }
    }

    /**
     * USING ctx.query(selections, variables) — subquery with a variables map
     *
     * Note: Subqueries do NOT inherit the parent request's GraphQL variables.
     * Two subqueries with the same selection string but different variables maps
     * are fully independent.
     */
    @Resolver
    class DoubledResolver : EnrichedGreetingResolvers.Doubled() {
        override suspend fun resolve(ctx: Context): Int {
            val input = ctx.arguments.input
            // Pass variable values explicitly — subqueries have their own variable scope
            val queryResult = ctx.query("multiply(n: \$n)", mapOf("n" to input))
            return queryResult.getMultiply() ?: 0
        }
    }

    // ======================= Mutation resolvers =======================

    @Resolver
    class StepResolver : MutationResolvers.Step() {
        override suspend fun resolve(ctx: Context): Int = ++counter
    }

    /**
     * USING ctx.mutation() — submutation from a mutation resolver
     *
     * What YOU write:
     * - Call ctx.mutation(selectionString) from a mutation resolver
     * - Access results via typed getters, same as ctx.query()
     *
     * What VIADUCT does:
     * - Executes the selection against the Mutation root
     * - Mutation fields execute serially (standard GraphQL mutation semantics)
     *
     * Important: ctx.mutation() is ONLY available in mutation resolver contexts.
     * The type system prevents calling it from query resolvers at compile time.
     * Mutation resolvers can also call ctx.query() freely.
     */
    @Resolver
    class PipelineResolver : MutationResolvers.Pipeline() {
        override suspend fun resolve(ctx: Context): String {
            // SUBMUTATION: call another mutation from this mutation resolver
            val mutationResult = ctx.mutation("step")
            val newCount = mutationResult.getStep() ?: 0

            // SUBQUERY: also fetch query data from the same resolver
            val queryResult = ctx.query("greeting")
            val greeting = queryResult.getGreeting() ?: "Hello"

            return "$greeting — step $newCount"
        }
    }

    // ======================= Tests =======================

    @Test
    fun `ctx query fetches greeting from Query root`() {
        execute(
            query = """
                query {
                    enriched {
                        message
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "enriched" to {
                    "message" to "Hello, World! (enriched)"
                }
            }
        }
    }

    @Test
    fun `ctx query with variables passes argument to subquery`() {
        execute(
            query = """
                query {
                    enriched {
                        doubled(input: 21)
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "enriched" to {
                    // multiply(n: 21) returns 21 * 2 = 42
                    "doubled" to 42
                }
            }
        }
    }

    @Test
    fun `ctx mutation executes submutation and ctx query fetches query data`() {
        counter = 0
        execute(
            query = """
                mutation {
                    pipeline
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "pipeline" to "Hello, World! — step 1"
            }
        }
    }
}
