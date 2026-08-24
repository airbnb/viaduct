@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.operationfromannotation

import viaduct.api.documents.FragmentFromAnnotation
import viaduct.api.documents.GraphQLFragment
import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.MutationFromAnnotation
import viaduct.api.documents.QueryFromAnnotation
import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.operationfromannotation.resolverbases.ContainerResolvers
import viaduct.tenant.runtime.execution.operationfromannotation.resolverbases.MutationResolvers
import viaduct.tenant.runtime.execution.operationfromannotation.resolverbases.QueryResolvers

// Statically-declared operations — executed below via ctx.query / ctx.mutation.

@GraphQLOperation("query(\$value: String!) { echo(value: \$value) }")
object EchoQuery : QueryFromAnnotation()

@GraphQLOperation("mutation(\$value: String!) { record(value: \$value) }")
object RecordMutation : MutationFromAnnotation()

// A named fragment spread by GreeterQuery below — it must resolve at the ctx.query boundary.
@GraphQLFragment("fragment GreeterFields on Greeter { text }")
object GreeterFieldsFragment : FragmentFromAnnotation<Greeter>()

@GraphQLOperation("{ greeter { ...GreeterFields } }")
object GreeterQuery : QueryFromAnnotation()

class KotlinOperationFromAnnotationContractTest : OperationFromAnnotationContractTest() {
    @Resolver
    class Query_ContainerResolver : QueryResolvers.Container() {
        override suspend fun resolve(ctx: Context): Container = Container.Builder(ctx).build()
    }

    @Resolver
    class Query_EchoResolver : QueryResolvers.Echo() {
        override suspend fun resolve(ctx: Context): String = "echo:${ctx.arguments.value}"
    }

    @Resolver
    class Query_GreeterResolver : QueryResolvers.Greeter() {
        override suspend fun resolve(ctx: Context): Greeter = Greeter.Builder(ctx).text("hi").build()
    }

    @Resolver
    class Mutation_RecordResolver : MutationResolvers.Record() {
        override suspend fun resolve(ctx: Context): String = "record:${ctx.arguments.value}"
    }

    @Resolver
    class Container_RunQueryWithFragmentResolver : ContainerResolvers.RunQueryWithFragment() {
        override suspend fun resolve(ctx: Context): String {
            val result = ctx.query(GreeterQuery)
            return result.getGreeterOrThrow()?.getTextOrThrow() ?: error("greeter was null")
        }
    }

    @Resolver
    class Container_RunQueryOperationResolver : ContainerResolvers.RunQueryOperation() {
        override suspend fun resolve(ctx: Context): String {
            val result = ctx.query(EchoQuery, mapOf("value" to ctx.arguments.value))
            return result.getEchoOrThrow()
        }
    }

    @Resolver
    class Mutation_RunMutationOperationResolver : MutationResolvers.RunMutationOperation() {
        override suspend fun resolve(ctx: Context): String {
            val result = ctx.mutation(RecordMutation, mapOf("value" to ctx.arguments.value))
            return result.getRecordOrThrow()
        }
    }
}
