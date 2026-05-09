@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.variables.providerexception

import viaduct.api.context.VariablesProviderContext
import viaduct.api.resolver.Resolver
import viaduct.api.resolver.Variables
import viaduct.api.resolver.VariablesProvider
import viaduct.api.types.Arguments
import viaduct.tenant.runtime.execution.variables.providerexception.resolverbases.QueryResolvers

/**
 * Tests for VariablesProvider.provide throwing exceptions during query execution.
 * The exception should be caught and turned into a GraphQL field error,
 * while the rest of the query execution should be successful.
 */
class VariablesProviderExceptionFeatureAppTest : VariablesProviderExceptionContractTest() {
    // VariablesProvider that throws an exception
    @Resolver(
        """
        fragment _ on Query {
            intermediary(arg: ${'$'}someVar)
        }
        """
    )
    class Query_FromVariablesProviderResolver : QueryResolvers.FromVariablesProvider() {
        override suspend fun resolve(ctx: Context): Int = ctx.getObjectValue().get("intermediary", Int::class)

        @Variables("someVar: Int!")
        class ThrowingVariablesProvider : VariablesProvider<Arguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments>): Map<String, Any?> {
                throw RuntimeException("Variables provider failed!")
            }
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

    @Resolver
    class Query_WorkingFieldResolver : QueryResolvers.WorkingField() {
        override suspend fun resolve(ctx: Context): String = "success"
    }
}
