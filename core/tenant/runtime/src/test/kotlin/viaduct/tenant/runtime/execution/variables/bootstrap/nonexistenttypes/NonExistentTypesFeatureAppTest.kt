@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.variables.bootstrap.nonexistenttypes

import viaduct.api.Resolver
import viaduct.api.Variables
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.types.Arguments
import viaduct.tenant.runtime.execution.variables.bootstrap.nonexistenttypes.resolverbases.QueryResolvers

/**
 * Tests for @Variables referencing non-existent types that should cause bootstrap failures.
 * Note: In the current implementation, type validation may occur at query time rather than bootstrap time.
 * This test uses invalid syntax to ensure bootstrap failure for demonstration purposes.
 */
class NonExistentTypesFeatureAppTest : NonExistentTypesContractTest() {
    // Non-existent type in @Variables - should fail at bootstrap
    @Resolver(
        """
        fragment _ on Query {
            intermediary(arg: ${'$'}someVar)
        }
        """
    )
    class Query_FromVariablesProviderResolver : QueryResolvers.FromVariablesProvider() {
        override suspend fun resolve(ctx: Context): Int = ctx.getObjectValue().get("intermediary", Int::class)

        @Variables("someVar:NonExistentType!")
        class NonExistentTypeVars : VariablesProvider<Arguments> {
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
