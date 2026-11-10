@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.requiredselections

import viaduct.api.documents.GraphQLOperation
import viaduct.api.documents.QueryFromAnnotation
import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.requiredselections.resolverbases.BarResolvers
import viaduct.tenant.runtime.execution.requiredselections.resolverbases.BazResolvers
import viaduct.tenant.runtime.execution.requiredselections.resolverbases.MutationResolvers
import viaduct.tenant.runtime.execution.requiredselections.resolverbases.QueryResolvers

@GraphQLOperation("{ initialString }")
object InitialStringQuery : QueryFromAnnotation()

@GraphQLOperation("{ baz { id x y } }")
object BazQuery : QueryFromAnnotation()

class KotlinRequiredSelectionsContractTest : RequiredSelectionsContractTest() {
    @Resolver
    class Query_SourceConnectionResolver : QueryResolvers.SourceConnection() {
        override suspend fun resolve(ctx: Context): BazConnection = BazConnection.Builder(ctx).build()
    }

    @Resolver(objectValueFragment = "fragment _ on Query { sourceConnection { __typename } }")
    class Query_InputConnectionCopyResolver : QueryResolvers.InputConnectionCopy() {
        override suspend fun resolve(ctx: Context): BazConnection = ctx.getObjectValue().getSourceConnectionOrThrow()!!.toBuilder().build()
    }

    @Resolver(objectValueFragment = "fragment _ on Query { baz { id x } }")
    class Query_InputObjectResolver : QueryResolvers.InputObject() {
        override suspend fun resolve(ctx: Context): Baz = ctx.getObjectValue().getBazOrThrow()!!
    }

    @Resolver(queryValueFragment = "fragment _ on Query { baz { id x } }")
    class Query_InputQueryResolver : QueryResolvers.InputQuery() {
        override suspend fun resolve(ctx: Context): Baz = ctx.getQueryValue().getBazOrThrow()!!
    }

    @Resolver(objectValueFragment = "fragment _ on Query { baz { id x } }")
    class Query_InputCopyResolver : QueryResolvers.InputCopy() {
        override suspend fun resolve(ctx: Context): Baz = ctx.getObjectValue().getBazOrThrow()!!.toBuilder().x(101).build()
    }

    @Resolver(queryValueFragment = "fragment _ on Query { baz { id x } }")
    class Query_NestedInputResolver : QueryResolvers.NestedInput() {
        override suspend fun resolve(ctx: Context): BazBox = BazBox.Builder(ctx).baz(ctx.getQueryValue().getBazOrThrow()).build()
    }

    @Resolver
    class Query_SubqueryOutputResolver : QueryResolvers.SubqueryOutput() {
        override suspend fun resolve(ctx: Context): Baz = ctx.query(BazQuery).getBazOrThrow()!!
    }

    @Resolver
    class Query_SubqueryCopyResolver : QueryResolvers.SubqueryCopy() {
        override suspend fun resolve(ctx: Context): Baz = ctx.query(BazQuery).getBazOrThrow()!!.toBuilder().build()
    }

    @Resolver(objectValueFragment = "fragment _ on Query { baz { id x } }")
    class Query_ScalarCopyResolver : QueryResolvers.ScalarCopy() {
        override suspend fun resolve(ctx: Context): Baz {
            val input = ctx.getObjectValue().getBazOrThrow()!!
            return Baz.Builder(ctx).id(input.getIdOrThrow()).x(input.getXOrThrow()).build()
        }
    }

    @Resolver
    class Query_GlobalConfigResolver : QueryResolvers.GlobalConfig() {
        override suspend fun resolve(ctx: Context): String = "B"
    }

    @Resolver
    class Query_BarResolver : QueryResolvers.Bar() {
        override suspend fun resolve(ctx: Context): Bar = Bar.Builder(ctx).value("B").build()
    }

    @Resolver
    class Query_BazResolver : QueryResolvers.Baz() {
        override suspend fun resolve(ctx: Context): Baz = Baz.Builder(ctx).id(ctx.globalIDFor(Baz.Reflection, "baz1")).x(100).build()
    }

    @Resolver(objectValueFragment = "fragment _ on Query { aliasedBar: bar { aliasedValue: value } }")
    class Query_String1Resolver : QueryResolvers.String1() {
        override suspend fun resolve(ctx: Context): String {
            val value = ctx.getObjectValue().getBarOrThrow("aliasedBar")?.getValueOrThrow("aliasedValue")
            return "A:$value"
        }
    }

    @Resolver
    class Query_InitialStringResolver : QueryResolvers.InitialString() {
        override suspend fun resolve(ctx: Context): String = "InitialValue"
    }

    @Resolver
    class Mutation_String1Resolver : MutationResolvers.String1() {
        override suspend fun resolve(ctx: Context): String {
            val current = ctx.query(InitialStringQuery).getInitialStringOrThrow()
            return "Mutated from: $current"
        }
    }

    @Resolver
    class Bar_ValueResolver : BarResolvers.Value() {
        override suspend fun resolve(ctx: Context): String? = "B"
    }

    @Resolver(
        objectValueFragment = "fragment _ on Baz { x }",
        queryValueFragment = "fragment _ on Query { globalConfig }"
    )
    class Baz_YResolver : BazResolvers.Y() {
        override suspend fun resolve(ctx: Context): String {
            val config = ctx.getQueryValue().getGlobalConfigOrThrow()
            val x = ctx.getObjectValue().getXOrThrow()
            return "$config item with value $x"
        }
    }
}
