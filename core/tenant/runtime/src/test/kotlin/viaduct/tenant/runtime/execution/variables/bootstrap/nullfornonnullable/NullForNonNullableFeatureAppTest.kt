@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.variables.bootstrap.nullfornonnullable

import viaduct.api.context.VariablesProviderContext
import viaduct.api.resolver.Resolver
import viaduct.api.resolver.Variables
import viaduct.api.resolver.VariablesProvider
import viaduct.api.types.Arguments
import viaduct.tenant.runtime.execution.variables.bootstrap.nullfornonnullable.resolverbases.QueryResolvers

/**
 * Tests for invalid GraphQL fragment syntax that causes bootstrap failures.
 * This test expects the tenant to fail to build due to invalid GraphQL syntax.
 */
class NullForNonNullableFeatureAppTest : NullForNonNullableContractTest() {
    @Resolver(
        """
        fragment _ on Query {
            intermediary(arg: ${'$'}intVar)
        }
        """
    )
    class Query_FromVariablesProviderResolver : QueryResolvers.FromVariablesProvider() {
        override suspend fun resolve(ctx: Context): Int = ctx.getObjectValue().get("intermediary", Int::class)

        @Variables("intVar:Int!")
        class NullForNonNullableVars : VariablesProvider<Arguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments>): Map<String, Any?> = mapOf("intVar" to null)
        }
    }

    @Resolver
    class Query_IntermediaryResolver : QueryResolvers.Intermediary() {
        override suspend fun resolve(ctx: Context): Int = ctx.arguments.arg
    }

    @Resolver
    class Query_FromArgumentFieldResolver : QueryResolvers.FromArgumentField() {
        override suspend fun resolve(ctx: Context): Int = ctx.arguments.arg
    }
}
