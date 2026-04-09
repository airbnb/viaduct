@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.variables.bootstrap.emptyvariables

import viaduct.api.Resolver
import viaduct.api.Variables
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.types.Arguments
import viaduct.tenant.runtime.execution.variables.bootstrap.emptyvariables.resolverbases.QueryResolvers

/**
 * Tests for empty @Variables string that causes bootstrap failures.
 * This test expects the tenant to fail to build due to empty variables definition.
 */
class EmptyVariablesFeatureAppTest : EmptyVariablesContractTest() {
    // Empty @Variables string - should fail at bootstrap
    @Resolver(
        """
        fragment _ on Query {
            intermediary(arg: ${'$'}someVar)
        }
        """
    )
    class Query_FromVariablesProviderResolver : QueryResolvers.FromVariablesProvider() {
        override suspend fun resolve(ctx: Context): Int? = ctx.objectValue.getIntermediary()

        @Variables("")
        class EmptyVariablesVars : VariablesProvider<Arguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments>): Map<String, Any?> = mapOf("someVar" to 42)
        }
    }

    // Need these resolvers to satisfy the schema
    @Resolver
    class Query_IntermediaryResolver : QueryResolvers.Intermediary() {
        override suspend fun resolve(ctx: Context): Int = ctx.arguments.arg
    }

    @Resolver
    class Query_FromArgumentFieldResolver : QueryResolvers.FromArgumentField() {
        override suspend fun resolve(ctx: Context): Int = ctx.arguments.arg
    }
}
