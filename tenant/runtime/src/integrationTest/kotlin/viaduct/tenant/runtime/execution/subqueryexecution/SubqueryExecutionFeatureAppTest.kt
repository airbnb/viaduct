@file:Suppress("unused", "ClassName", "PackageDirectoryMismatch")

package viaduct.tenant.runtime.execution.subqueryexecution

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.CalculatorResolvers
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.ContainerResolvers
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.Level1Resolvers
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.Level2Resolvers
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.MutationResolvers
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.subqueryexecution.resolverbases.UserResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Feature tests for the tenant-facing ctx.query() and ctx.mutation() APIs.
 *
 * These tests validate that resolvers can execute subqueries against the GraphQL schema
 * using the ExecutionHandle-based subquery execution path. This covers:
 *
 * 1. ctx.query() - Execute subqueries against the Query root (from field and mutation resolvers)
 * 2. ctx.mutation() - Execute subqueries against the Mutation root (from mutation resolvers)
 * 3. Variable passing and field arguments in subqueries
 * 4. Nested subquery execution
 * 5. Comparison of queryValueFragment vs ctx.query() patterns
 *
 * Recursive mutation-to-mutation subqueries (self-recursion) are covered in
 * [RecursiveSubmutationFeatureAppTest].
 *
 * Note: ctx.mutation() is only available from MutationFieldExecutionContext (mutation resolvers).
 */
class SubqueryExecutionFeatureAppTest : FeatureAppTestBase() {
    @BeforeEach
    override fun initViaductBuilder() {
        super.initViaductBuilder()
        withViaductBuilder {
            withFlagManager(MockFlagManager.mk(FlagManager.Flags.ENABLE_SUBQUERY_EXECUTION_VIA_HANDLE))
        }
    }

    override var sdl = """
        #START_SCHEMA
        extend type Query {
            rootValue: Int @resolver
            firstName: String @resolver
            lastName: String @resolver
            multiply(n: Int!): Int @resolver
            container: Container @resolver
            user: User @resolver
            calculator: Calculator @resolver
            level1: Level1 @resolver
            baseValue: Int @resolver
            counterValue: Int @resolver
        }

        extend type Mutation {
            incrementCounter: Int @resolver
            triggerNestedMutation: Int @resolver
            fetchFromQueryDuringMutation: String @resolver
        }

        type Container {
            derivedFromQuery: Int @resolver
            viaQuerySelections: Int @resolver
            viaCtxQuery: Int @resolver
        }

        type User {
            fullName: String @resolver
        }

        type Calculator {
            double(input: Int!): Int @resolver
        }

        type Level1 {
            level2: Level2 @resolver
        }

        type Level2 {
            derivedValue: Int @resolver
        }
        #END_SCHEMA
    """

    companion object {
        var counter = 0
    }

    // Simple resolver that returns a static value
    @Resolver
    class Query_RootValueResolver : QueryResolvers.RootValue() {
        override suspend fun resolve(ctx: Context): Int = 42
    }

    @Resolver
    class Query_FirstNameResolver : QueryResolvers.FirstName() {
        override suspend fun resolve(ctx: Context): String = "Alice"
    }

    @Resolver
    class Query_LastNameResolver : QueryResolvers.LastName() {
        override suspend fun resolve(ctx: Context): String = "Smith"
    }

    @Resolver
    class Query_MultiplyResolver : QueryResolvers.Multiply() {
        override suspend fun resolve(ctx: Context): Int {
            val n = ctx.arguments.n
            return n * 2
        }
    }

    @Resolver
    class Query_ContainerResolver : QueryResolvers.Container() {
        override suspend fun resolve(ctx: Context): Container {
            return Container.Builder(ctx).build()
        }
    }

    @Resolver
    class Query_UserResolver : QueryResolvers.User() {
        override suspend fun resolve(ctx: Context): User {
            return User.Builder(ctx).build()
        }
    }

    @Resolver
    class Query_CalculatorResolver : QueryResolvers.Calculator() {
        override suspend fun resolve(ctx: Context): Calculator {
            return Calculator.Builder(ctx).build()
        }
    }

    @Resolver
    class Query_Level1Resolver : QueryResolvers.Level1() {
        override suspend fun resolve(ctx: Context): Level1 {
            return Level1.Builder(ctx).build()
        }
    }

    @Resolver
    class Query_BaseValueResolver : QueryResolvers.BaseValue() {
        override suspend fun resolve(ctx: Context): Int = 10
    }

    @Resolver
    class Query_CounterValueResolver : QueryResolvers.CounterValue() {
        override suspend fun resolve(ctx: Context): Int = counter
    }

    @Resolver
    class Mutation_IncrementCounterResolver : MutationResolvers.IncrementCounter() {
        override suspend fun resolve(ctx: Context): Int {
            return ++counter
        }
    }

    /**
     * Mutation resolver that uses ctx.mutation() to execute a nested mutation subquery.
     * This demonstrates that mutation resolvers can call other mutations via ctx.mutation().
     */
    @Resolver
    class Mutation_TriggerNestedMutationResolver : MutationResolvers.TriggerNestedMutation() {
        override suspend fun resolve(ctx: Context): Int {
            val selections = ctx.selectionsFor(Mutation.Reflection, "incrementCounter")
            val mutationResult = ctx.mutation(selections)
            return mutationResult.getIncrementCounter() ?: 0
        }
    }

    /**
     * Mutation resolver that uses ctx.query() to fetch Query data during mutation execution.
     * This demonstrates that mutation resolvers can also access Query root data.
     */
    @Resolver
    class Mutation_FetchFromQueryDuringMutationResolver : MutationResolvers.FetchFromQueryDuringMutation() {
        override suspend fun resolve(ctx: Context): String {
            val selections = ctx.selectionsFor(Query.Reflection, "firstName lastName")
            val queryResult = ctx.query(selections)
            val first = queryResult.getFirstName() ?: ""
            val last = queryResult.getLastName() ?: ""
            return "Mutation processed for: $first $last"
        }
    }

    /**
     * Resolver that uses ctx.query() to fetch data from the Query root
     * and derives a value from it.
     */
    @Resolver
    class Container_DerivedFromQueryResolver : ContainerResolvers.DerivedFromQuery() {
        override suspend fun resolve(ctx: Context): Int {
            val selections = ctx.selectionsFor(Query.Reflection, "rootValue")
            val queryResult = ctx.query(selections)
            val rootValue = queryResult.getRootValue() ?: 0
            return rootValue * 2
        }
    }

    /**
     * Resolver that demonstrates the queryValueFragment pattern (existing approach).
     * This uses the @Resolver annotation's queryValueFragment parameter.
     */
    @Resolver(queryValueFragment = "fragment _ on Query { rootValue }")
    class Container_ViaQuerySelectionsResolver : ContainerResolvers.ViaQuerySelections() {
        override suspend fun resolve(ctx: Context): Int {
            return ctx.queryValue.getRootValue() ?: 0
        }
    }

    /**
     * Resolver that demonstrates ctx.query() pattern (new approach via ExecutionHandle).
     */
    @Resolver
    class Container_ViaCtxQueryResolver : ContainerResolvers.ViaCtxQuery() {
        override suspend fun resolve(ctx: Context): Int {
            val selections = ctx.selectionsFor(Query.Reflection, "rootValue")
            val result = ctx.query(selections)
            return result.getRootValue() ?: 0
        }
    }

    /**
     * Resolver that fetches multiple fields from Query root in one subquery.
     */
    @Resolver
    class User_FullNameResolver : UserResolvers.FullName() {
        override suspend fun resolve(ctx: Context): String {
            val selections = ctx.selectionsFor(Query.Reflection, "firstName lastName")
            val queryResult = ctx.query(selections)
            val first = queryResult.getFirstName() ?: ""
            val last = queryResult.getLastName() ?: ""
            return "$first $last"
        }
    }

    /**
     * Resolver that executes a subquery with field arguments using inline literal values.
     *
     * Note: Subqueries do NOT inherit the parent request's GraphQL variables. You can either:
     * - Use inline literal values in the selection string (as shown here): `"multiply(n: $input)"`
     * - Pass a variables map to selectionsFor: `ctx.selectionsFor(Type, "multiply(n: \$n)", mapOf("n" to input))`
     */
    @Resolver
    class Calculator_DoubleResolver : CalculatorResolvers.Double() {
        override suspend fun resolve(ctx: Context): Int {
            val input = ctx.arguments.input
            val selections = ctx.selectionsFor(
                Query.Reflection,
                "multiply(n: $input)"
            )
            val queryResult = ctx.query(selections)
            return queryResult.getMultiply() ?: 0
        }
    }

    @Resolver
    class Level1_Level2Resolver : Level1Resolvers.Level2() {
        override suspend fun resolve(ctx: Context): Level2 {
            return Level2.Builder(ctx).build()
        }
    }

    /**
     * Resolver that executes nested subquery (a subquery within a nested resolver).
     * This tests that subquery execution works at any depth.
     */
    @Resolver
    class Level2_DerivedValueResolver : Level2Resolvers.DerivedValue() {
        override suspend fun resolve(ctx: Context): Int {
            val selections = ctx.selectionsFor(Query.Reflection, "baseValue")
            val result = ctx.query(selections)
            return (result.getBaseValue() ?: 0) * 3
        }
    }

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
        counter = 0
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
}
