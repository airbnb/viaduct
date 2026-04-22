@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.variables.bootstrap.oneofviolation

import viaduct.api.Resolver
import viaduct.api.Variables
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.types.Arguments
import viaduct.tenant.runtime.execution.variables.bootstrap.oneofviolation.resolverbases.QueryResolvers

/**
 * Tests for @oneOf input validation that should cause runtime failures.
 * This test expects the tenant to build successfully but queries to fail at runtime due to oneof violations.
 */
class TempOneOfViolationFeatureAppTest : TempOneOfViolationContractTest() {
    @Resolver(
        """
        fragment _ on Query {
            intermediary(arg: ${'$'}oneofVar)
        }
        """
    )
    class Query_FromVariablesProviderResolver : QueryResolvers.FromVariablesProvider() {
        override suspend fun resolve(ctx: Context): String? = ctx.getObjectValue().getIntermediary()

        @Variables("oneofVar: OneofInput!")
        class OneOfViolationVars : VariablesProvider<Arguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments>): Map<String, Any?> =
                mapOf(
                    "oneofVar" to mapOf(
                        "stringValue" to "test",
                        "intValue" to 42
                    )
                )
        }
    }

    @Resolver
    class Query_IntermediaryResolver : QueryResolvers.Intermediary() {
        override suspend fun resolve(ctx: Context): String = ctx.arguments.arg.toString()
    }

    @Resolver
    class Query_FromArgumentFieldResolver : QueryResolvers.FromArgumentField() {
        override suspend fun resolve(ctx: Context): String = ctx.arguments.arg.toString()
    }
}
