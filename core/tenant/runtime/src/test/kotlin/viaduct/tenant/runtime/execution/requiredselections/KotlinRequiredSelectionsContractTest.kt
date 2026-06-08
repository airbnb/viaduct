@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.requiredselections

import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.requiredselections.resolverbases.BarResolvers
import viaduct.tenant.runtime.execution.requiredselections.resolverbases.BazResolvers
import viaduct.tenant.runtime.execution.requiredselections.resolverbases.MutationResolvers
import viaduct.tenant.runtime.execution.requiredselections.resolverbases.QueryResolvers

class KotlinRequiredSelectionsContractTest : RequiredSelectionsContractTest() {
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
            val value = ctx.getObjectValue().getBar("aliasedBar")?.getValue("aliasedValue")
            return "A:$value"
        }
    }

    @Resolver
    class Query_InitialStringResolver : QueryResolvers.InitialString() {
        override suspend fun resolve(ctx: Context): String = "InitialValue"
    }

    @Resolver(queryValueFragment = "fragment _ on Query { initialString }")
    class Mutation_String1Resolver : MutationResolvers.String1() {
        override suspend fun resolve(ctx: Context): String {
            val current = ctx.getQueryValue().getInitialString()
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
            val config = ctx.getQueryValue().getGlobalConfig()
            val x = ctx.getObjectValue().getX()
            return "$config item with value $x"
        }
    }
}
