@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.roottypes

import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.roottypes.resolverbases.CustomMutationResolvers
import viaduct.tenant.runtime.execution.roottypes.resolverbases.CustomQueryResolvers

class RootTypesSchemaClauseFeatureAppTest : RootTypesSchemaClauseContractTest() {
    @Resolver
    class CustomQuery_GreetingResolver : CustomQueryResolvers.Greeting() {
        override suspend fun resolve(ctx: Context): String {
            return "Hello, ${ctx.arguments.name}!"
        }
    }

    @Resolver
    class CustomQuery_EchoResolver : CustomQueryResolvers.Echo() {
        override suspend fun resolve(ctx: Context): String {
            return ctx.arguments.message
        }
    }

    @Resolver
    class CustomMutation_SaveMessageResolver : CustomMutationResolvers.SaveMessage() {
        override suspend fun resolve(ctx: Context): SaveMessagePayload {
            val messageId = "msg-${ctx.arguments.content.hashCode()}"
            return SaveMessagePayload.Builder(ctx)
                .messageId(messageId)
                .content(ctx.arguments.content)
                .build()
        }
    }
}
