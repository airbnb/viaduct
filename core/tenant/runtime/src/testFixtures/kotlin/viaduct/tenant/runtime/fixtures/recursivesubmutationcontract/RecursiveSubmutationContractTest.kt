package viaduct.tenant.runtime.fixtures.recursivesubmutationcontract

import org.junit.jupiter.api.Test
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Contract test for recursive ctx.mutation() subqueries.
 *
 * Defines the SDL and assertions for:
 * - Recursive mutation resolver computing a triangular number
 * - Base case handling for the recursive mutation
 *
 * Note: This contract test is Kotlin-only because ctx.mutation() is not available
 * in the Java Tenant API.
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
abstract class RecursiveSubmutationContractTest : FeatureAppTestBase() {
    init {
        sdl = """
            |#START_SCHEMA
            |extend type Mutation {
            |  "Recursively compute triangular number via ctx.mutation(): if triangleSize<=1 return 1, else return triangleSize + recurse(triangleSize-1). E.g. 4→10, 1→1"
            |  exampleMutationSelections(triangleSize: Int!): Int @resolver
            |}
            |#END_SCHEMA
        """.trimMargin()
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
