@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.variables.bootstrap.invalidsyntax

import viaduct.api.context.VariablesProviderContext
import viaduct.api.resolver.Resolver
import viaduct.api.resolver.Variables
import viaduct.api.resolver.VariablesProvider
import viaduct.api.types.Arguments
import viaduct.tenant.runtime.execution.variables.bootstrap.invalidsyntax.resolverbases.QueryResolvers

/**
 * Tests for syntactically invalid @Variables string that causes bootstrap failures.
 * This test expects the tenant to fail to build due to invalid GraphQL variable syntax.
 */
class InvalidSyntaxFeatureAppTest : InvalidSyntaxContractTest() {
    // Invalid syntax in @Variables string - should fail at bootstrap
    @Resolver(
        """
        fragment _ on Query {
            intermediary(arg: ${'$'}someVar)
        }
        """
    )
    class Query_FromVariablesProviderResolver : QueryResolvers.FromVariablesProvider() {
        override suspend fun resolve(ctx: Context) = ctx.getObjectValue().getIntermediaryOrThrow()

        @Variables("someVar Int! invalid syntax here")
        class InvalidSyntaxVars : VariablesProvider<Arguments> {
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
