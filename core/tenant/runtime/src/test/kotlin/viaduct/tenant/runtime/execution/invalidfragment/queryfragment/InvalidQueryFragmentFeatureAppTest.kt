@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.invalidfragment.queryfragment

import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.invalidfragment.queryfragment.resolverbases.FooResolvers
import viaduct.tenant.runtime.execution.invalidfragment.queryfragment.resolverbases.QueryResolvers

// Test for a query value fragment that is invalid
class InvalidQueryFragmentFeatureAppTest : InvalidQueryFragmentContractTest() {
    @Resolver(queryValueFragment = "horse")
    class Query_GreetingResolver : QueryResolvers.Greeting() {
        override suspend fun resolve(ctx: Context) = Foo.Builder(ctx).build()
    }

    @Resolver
    class Foo_BazResolver : FooResolvers.Baz() {
        override suspend fun resolve(ctx: Context) = "world"
    }

    // Delegates to baz using selection list syntax
    @Resolver
    class Foo_BarResolver : FooResolvers.Bar() {
        override suspend fun resolve(ctx: Context) = ctx.getObjectValue().get<String>("baz", String::class)
    }
}
