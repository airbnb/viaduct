@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.roottypes

import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.MutationFromAnnotation
import viaduct.api.documents.QueryFromAnnotation
import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.roottypes.resolverbases.CustomMutationResolvers
import viaduct.tenant.runtime.execution.roottypes.resolverbases.CustomQueryResolvers

@GraphQLOperation("query(\$name: String!) { greeting(name: \$name) }")
object GreetingQuery : QueryFromAnnotation()

@GraphQLOperation("mutation(\$content: String!) { saveMessage(content: \$content) { messageId content } }")
object SaveMessageMutation : MutationFromAnnotation()

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

    @Resolver(queryValueFragment = "fragment _ on CustomQuery { greeting(name: \"Selection\") }")
    class CustomQuery_SelectedGreetingResolver : CustomQueryResolvers.SelectedGreeting() {
        override suspend fun resolve(ctx: Context): String = ctx.getQueryValue().getGreetingOrThrow() ?: ""
    }

    @Resolver
    class CustomQuery_QueriedGreetingResolver : CustomQueryResolvers.QueriedGreeting() {
        override suspend fun resolve(ctx: Context): String {
            return ctx.query(GreetingQuery, mapOf("name" to ctx.arguments.name)).getGreetingOrThrow() ?: ""
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

    @Resolver
    class CustomMutation_RelayMessageResolver : CustomMutationResolvers.RelayMessage() {
        override suspend fun resolve(ctx: Context): SaveMessagePayload? {
            return ctx.mutation(SaveMessageMutation, mapOf("content" to ctx.arguments.content)).getSaveMessageOrThrow()
        }
    }
}
