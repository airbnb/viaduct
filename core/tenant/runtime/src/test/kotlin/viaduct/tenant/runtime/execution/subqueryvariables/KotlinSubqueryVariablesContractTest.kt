@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.subqueryvariables

import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.QueryFromAnnotation
import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.subqueryvariables.resolverbases.ContainerResolvers
import viaduct.tenant.runtime.execution.subqueryvariables.resolverbases.QueryResolvers

@GraphQLOperation("query(\$input: SubqueryInput!) { echoInput(input: \$input) }")
object EchoInputQuery : QueryFromAnnotation()

class KotlinSubqueryVariablesContractTest : SubqueryVariablesContractTest() {
    @Resolver
    class Query_ContainerResolver : QueryResolvers.Container() {
        override suspend fun resolve(ctx: Context): Container = Container.Builder(ctx).build()
    }

    @Resolver
    class Query_EchoInputResolver : QueryResolvers.EchoInput() {
        override suspend fun resolve(ctx: Context): String {
            val input = ctx.arguments.input
            return "${input.count}:${input.statuses.joinToString()}"
        }
    }

    @Resolver
    class Container_QueryWithInputVariableResolver : ContainerResolvers.QueryWithInputVariable() {
        override suspend fun resolve(ctx: Context): String {
            val result = ctx.query(EchoInputQuery, mapOf("input" to ctx.arguments.input))
            return result.getEchoInput()
        }
    }
}
