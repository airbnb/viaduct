package viaduct.tenant.runtime.execution.subqueryexecution

import org.junit.jupiter.api.Test
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.runtime.fixtures.TestSchema

/**
 * Contract test for the ctx.query() and ctx.mutation() subquery execution APIs.
 *
 * Defines the SDL and assertions for:
 * - ctx.query() executing a subquery against the Query root from a field resolver
 * - ctx.query() accessing multiple Query fields in one subquery
 * - ctx.query() with inline field arguments
 * - ctx.mutation() executing a nested mutation subquery from a mutation resolver
 * - Mutation resolver using ctx.query() to fetch Query data
 * - queryValueFragment pattern as an alternative to ctx.query() for simple cases
 * - Nested subquery execution at depth (Level1 → Level2)
 * - ctx.query(selections, variables) with a variables map
 * - ctx.mutation() result combined with a field argument
 * - ctx.query(selections, variables) from a mutation context
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
@TestSchema(
    """
    extend type Query {
        "Return 42"
        rootValue: Int @resolver
        "Return \"Alice\""
        firstName: String @resolver
        "Return \"Smith\""
        lastName: String @resolver
        "Return n * 2"
        multiply(n: Int!): Int @resolver
        "Return a Container object"
        container: Container @resolver
        "Return a User object"
        user: User @resolver
        "Return a Calculator object"
        calculator: Calculator @resolver
        "Return a Level1 object"
        level1: Level1 @resolver
        "Return 10"
        baseValue: Int @resolver
        "Return current counter value (mutable state, starts at 0)"
        counterValue: Int @resolver
    }

    extend type Mutation {
        "Increment a mutable counter; return new value (1, 2, 3, ...)"
        incrementCounter: Int @resolver
        "Use ctx.mutation() to call incrementCounter; return the counter value"
        triggerNestedMutation: Int @resolver
        "Use ctx.query() for firstName+lastName; return \"Mutation processed for: Alice Smith\""
        fetchFromQueryDuringMutation: String @resolver
        "Use ctx.mutation() to call incrementCounter (counter=1), return counter * multiplier"
        mutationWithVariables(multiplier: Int!): Int @resolver
        "Use ctx.query() with variables to call multiply(n: n); return n * 2"
        queryWithVariablesFromMutation(n: Int!): Int @resolver
    }

    type Container {
        "Use ctx.query() to fetch rootValue; return rootValue * 2 (=84)"
        derivedFromQuery: Int @resolver
        "Use queryValueFragment to get rootValue; return 42"
        viaQuerySelections: Int @resolver
        "Use ctx.query() to get rootValue; return 42"
        viaCtxQuery: Int @resolver
        "Use ctx.query() with variables to call multiply(n: multiplier); return multiplier * 2"
        queryWithVariables(multiplier: Int!): Int @resolver
    }

    type User {
        "Use ctx.query() to fetch firstName and lastName; return \"Alice Smith\""
        fullName: String @resolver
    }

    type Calculator {
        "Use ctx.query() with arguments to call multiply(n: input); return input * 2"
        double(input: Int!): Int @resolver
    }

    type Level1 {
        "Return a Level2 object"
        level2: Level2 @resolver
    }

    type Level2 {
        "Use ctx.query() to fetch baseValue; return baseValue * 3 (=30)"
        derivedValue: Int @resolver
    }
"""
)
abstract class SubqueryExecutionContractTest : FeatureAppTestBase() {
    /**
     * Hook for subclasses to reset mutable counter state before counter-sensitive tests.
     * The default no-op implementation is sufficient for stateless implementations.
     */
    protected open fun resetCounter() {}

    @Test
    fun `ctx query executes subquery against Query root`() {
        execute(
            query = """
                query {
                    container {
                        derivedFromQuery
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "container" to {
                    "derivedFromQuery" to 84
                }
            }
        }
    }

    @Test
    fun `ctx query accesses multiple Query fields`() {
        execute(
            query = """
                query {
                    user {
                        fullName
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "user" to {
                    "fullName" to "Alice Smith"
                }
            }
        }
    }

    @Test
    fun `ctx query with field arguments`() {
        execute(
            query = """
                query {
                    calculator {
                        double(input: 21)
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "calculator" to {
                    "double" to 42
                }
            }
        }
    }

    @Test
    fun `ctx mutation executes nested mutation subquery from mutation resolver`() {
        resetCounter()
        execute(
            query = """
                mutation {
                    triggerNestedMutation
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "triggerNestedMutation" to 1
            }
        }

        execute(
            query = """
                mutation {
                    triggerNestedMutation
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "triggerNestedMutation" to 2
            }
        }
    }

    @Test
    fun `mutation resolver uses ctx query to fetch Query data`() {
        execute(
            query = """
                mutation {
                    fetchFromQueryDuringMutation
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "fetchFromQueryDuringMutation" to "Mutation processed for: Alice Smith"
            }
        }
    }

    @Test
    fun `querySelections provides alternative to ctx query for simple cases`() {
        execute(
            query = """
                query {
                    container {
                        viaQuerySelections
                        viaCtxQuery
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "container" to {
                    "viaQuerySelections" to 42
                    "viaCtxQuery" to 42
                }
            }
        }
    }

    @Test
    fun `nested subquery execution`() {
        execute(
            query = """
                query {
                    level1 {
                        level2 {
                            derivedValue
                        }
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "level1" to {
                    "level2" to {
                        "derivedValue" to 30
                    }
                }
            }
        }
    }

    @Test
    fun `ctx query with variables map`() {
        execute(
            query = """
                query {
                    container {
                        queryWithVariables(multiplier: 5)
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "container" to {
                    // multiply(n: 5) returns 5 * 2 = 10
                    "queryWithVariables" to 10
                }
            }
        }
    }

    @Test
    fun `ctx mutation result combined with argument`() {
        resetCounter()
        execute(
            query = """
                mutation {
                    mutationWithVariables(multiplier: 10)
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                // counter becomes 1, then 1 * 10 = 10
                "mutationWithVariables" to 10
            }
        }
    }

    @Test
    fun `ctx query with variables map from mutation context`() {
        execute(
            query = """
                mutation {
                    queryWithVariablesFromMutation(n: 7)
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                // multiply(n: 7) returns 7 * 2 = 14
                "queryWithVariablesFromMutation" to 14
            }
        }
    }
}
