@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.subquerycontract

import org.junit.jupiter.api.BeforeEach
import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.subquerycontract.resolverbases.CalculatorResolvers
import viaduct.tenant.runtime.execution.subquerycontract.resolverbases.ContainerResolvers
import viaduct.tenant.runtime.execution.subquerycontract.resolverbases.Level1Resolvers
import viaduct.tenant.runtime.execution.subquerycontract.resolverbases.Level2Resolvers
import viaduct.tenant.runtime.execution.subquerycontract.resolverbases.MutationResolvers
import viaduct.tenant.runtime.execution.subquerycontract.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.subquerycontract.resolverbases.UserResolvers
import viaduct.tenant.runtime.fixtures.subqueryexecutioncontract.SubqueryExecutionContractTest

class KotlinSubqueryExecutionContractTest : SubqueryExecutionContractTest() {
    companion object {
        var counter = 0
    }

    @BeforeEach
    fun resetCounterBeforeTest() {
        counter = 0
    }

    override fun resetCounter() {
        counter = 0
    }

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
        override suspend fun resolve(ctx: Context): Int = ctx.arguments.n * 2
    }

    @Resolver
    class Query_ContainerResolver : QueryResolvers.Container() {
        override suspend fun resolve(ctx: Context): Container = Container.Builder(ctx).build()
    }

    @Resolver
    class Query_UserResolver : QueryResolvers.User() {
        override suspend fun resolve(ctx: Context): User = User.Builder(ctx).build()
    }

    @Resolver
    class Query_CalculatorResolver : QueryResolvers.Calculator() {
        override suspend fun resolve(ctx: Context): Calculator = Calculator.Builder(ctx).build()
    }

    @Resolver
    class Query_Level1Resolver : QueryResolvers.Level1() {
        override suspend fun resolve(ctx: Context): Level1 = Level1.Builder(ctx).build()
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
        override suspend fun resolve(ctx: Context): Int = ++counter
    }

    @Resolver
    class Mutation_TriggerNestedMutationResolver : MutationResolvers.TriggerNestedMutation() {
        override suspend fun resolve(ctx: Context): Int {
            val mutationResult = ctx.mutation("incrementCounter")
            return mutationResult.getIncrementCounter() ?: 0
        }
    }

    @Resolver
    class Mutation_FetchFromQueryDuringMutationResolver : MutationResolvers.FetchFromQueryDuringMutation() {
        override suspend fun resolve(ctx: Context): String {
            val queryResult = ctx.query("firstName lastName")
            val first = queryResult.getFirstName() ?: ""
            val last = queryResult.getLastName() ?: ""
            return "Mutation processed for: $first $last"
        }
    }

    @Resolver
    class Container_DerivedFromQueryResolver : ContainerResolvers.DerivedFromQuery() {
        override suspend fun resolve(ctx: Context): Int {
            val queryResult = ctx.query("rootValue")
            val rootValue = queryResult.getRootValue() ?: 0
            return rootValue * 2
        }
    }

    @Resolver(queryValueFragment = "fragment _ on Query { rootValue }")
    class Container_ViaQuerySelectionsResolver : ContainerResolvers.ViaQuerySelections() {
        override suspend fun resolve(ctx: Context): Int = ctx.queryValue.getRootValue() ?: 0
    }

    @Resolver
    class Container_ViaCtxQueryResolver : ContainerResolvers.ViaCtxQuery() {
        override suspend fun resolve(ctx: Context): Int {
            val result = ctx.query("rootValue")
            return result.getRootValue() ?: 0
        }
    }

    @Resolver
    class User_FullNameResolver : UserResolvers.FullName() {
        override suspend fun resolve(ctx: Context): String {
            val queryResult = ctx.query("firstName lastName")
            val first = queryResult.getFirstName() ?: ""
            val last = queryResult.getLastName() ?: ""
            return "$first $last"
        }
    }

    @Resolver
    class Calculator_DoubleResolver : CalculatorResolvers.Double() {
        override suspend fun resolve(ctx: Context): Int {
            val input = ctx.arguments.input
            val queryResult = ctx.query("multiply(n: $input)")
            return queryResult.getMultiply() ?: 0
        }
    }

    @Resolver
    class Level1_Level2Resolver : Level1Resolvers.Level2() {
        override suspend fun resolve(ctx: Context): Level2 = Level2.Builder(ctx).build()
    }

    @Resolver
    class Level2_DerivedValueResolver : Level2Resolvers.DerivedValue() {
        override suspend fun resolve(ctx: Context): Int {
            val result = ctx.query("baseValue")
            return (result.getBaseValue() ?: 0) * 3
        }
    }

    @Resolver
    class Container_QueryWithVariablesResolver : ContainerResolvers.QueryWithVariables() {
        override suspend fun resolve(ctx: Context): Int {
            val multiplier = ctx.arguments.multiplier
            val result = ctx.query("multiply(n: \$n)", mapOf("n" to multiplier))
            return result.getMultiply() ?: 0
        }
    }

    @Resolver
    class Mutation_MutationWithVariablesResolver : MutationResolvers.MutationWithVariables() {
        override suspend fun resolve(ctx: Context): Int {
            val multiplier = ctx.arguments.multiplier
            val mutationResult = ctx.mutation("incrementCounter")
            val counterValue = mutationResult.getIncrementCounter() ?: 0
            return counterValue * multiplier
        }
    }

    @Resolver
    class Mutation_QueryWithVariablesFromMutationResolver : MutationResolvers.QueryWithVariablesFromMutation() {
        override suspend fun resolve(ctx: Context): Int {
            val n = ctx.arguments.n
            val queryResult = ctx.query("multiply(n: \$n)", mapOf("n" to n))
            return queryResult.getMultiply() ?: 0
        }
    }
}
