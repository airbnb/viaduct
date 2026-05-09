@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.variables.bootstrap.defaults

import viaduct.api.resolver.Resolver
import viaduct.api.resolver.Variable
import viaduct.tenant.runtime.execution.variables.bootstrap.defaults.resolverbases.QueryResolvers

/**
 * Tests for queries and resolvers that exercise inputs with default values.
 */
class DefaultsFeatureAppTest : DefaultsContractTest() {
    @Resolver("fragment _ on Query { inner(inp: {}) }")
    class Query_Outer1Resolver : QueryResolvers.Outer1() {
        override suspend fun resolve(ctx: Context): Int = ctx.getObjectValue().getInner() * 3
    }

    @Resolver("fragment _ on Query { inner }")
    class Query_Outer2Resolver : QueryResolvers.Outer2() {
        override suspend fun resolve(ctx: Context): Int = ctx.getObjectValue().getInner() * 5
    }

    @Resolver
    class Query_Outer3Resolver : QueryResolvers.Outer3() {
        override suspend fun resolve(ctx: Context): Int = ctx.arguments.arg.x * 7
    }

    @Resolver(
        "fragment _ on Query { inner(inp: \$var) } ",
        variables = [ Variable(name = "var", fromArgument = "arg") ]
    )
    class Query_Outer4Resolver : QueryResolvers.Outer4() {
        override suspend fun resolve(ctx: Context): Int = ctx.getObjectValue().getInner() * 11
    }

    @Resolver
    class Query_InnerResolver : QueryResolvers.Inner() {
        override suspend fun resolve(ctx: Context): Int =
            ctx.arguments.inp?.let { it.x * 2 }
                ?: -1
    }
}
