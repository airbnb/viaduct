package viaduct.tenant.runtime.execution.submutations

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager
import viaduct.tenant.runtime.execution.submutations.resolverbases.MutationResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Feature tests for recursive ctx.mutation() subqueries.
 *
 * This focuses on scenarios where a mutation resolver calls ctx.mutation() recursively
 * (self-recursion) with field arguments, to compute a triangular number.
 *
 * General ctx.query()/ctx.mutation() behavior (including non-recursive mutation subqueries,
 * ctx.query() from field and mutation resolvers, nested subqueries, and
 * queryValueFragment vs ctx.query() comparison) is covered in
 * [SubqueryExecutionFeatureAppTest].
 */
class RecursiveSubmutationFeatureAppTest : FeatureAppTestBase() {
    @BeforeEach
    override fun initViaductBuilder() {
        super.initViaductBuilder()
        withViaductBuilder {
            withFlagManager(MockFlagManager.mk(FlagManager.Flags.ENABLE_SUBQUERY_EXECUTION_VIA_HANDLE))
        }
    }

    override var sdl =
        """
        |#START_SCHEMA
        |extend type Mutation {
        |  exampleMutationSelections(triangleSize: Int!): Int @resolver
        |}
        |#END_SCHEMA
        """.trimMargin()

    /**
     * Resolver that recursively calls ctx.mutation() to compute a triangular number.
     * triangleSum(n) = n + triangleSum(n-1), with base case triangleSum(1) = 1.
     *
     * Note: Subqueries do NOT inherit the parent request's GraphQL variables. You can either:
     * - Use inline literal values in the selection string (as shown here)
     * - Pass a variables map to selectionsFor: `ctx.selectionsFor(Type, "field(arg: \$var)", mapOf("var" to value))`
     */
    @Resolver
    class Mutation_ExampleMutationSelections : MutationResolvers.ExampleMutationSelections() {
        override suspend fun resolve(ctx: Context): Int? {
            val size = ctx.arguments.triangleSize
            return when (size) {
                1 -> 1
                else -> {
                    // Use inline literal value for the argument, not a variable
                    val mutation = ctx.mutation(
                        ctx.selectionsFor(
                            Mutation.Reflection,
                            "exampleMutationSelections(triangleSize: ${size - 1})"
                        )
                    )
                    size + mutation.getExampleMutationSelections()!!
                }
            }
        }
    }

    @Test
    fun `recursive ctx mutation computes triangular number`() {
        // triangleSum(4) = 4 + 3 + 2 + 1 = 10
        execute(
            query = """
            mutation {
                exampleMutationSelections(triangleSize: 4)
            }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "exampleMutationSelections" to 10
            }
        }
    }

    @Test
    fun `recursive ctx mutation base case`() {
        // triangleSum(1) = 1 (base case, no recursion)
        execute(
            query = """
            mutation {
                exampleMutationSelections(triangleSize: 1)
            }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "exampleMutationSelections" to 1
            }
        }
    }
}
