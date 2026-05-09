@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.invalidfragment.objectfragment

import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.invalidfragment.objectfragment.resolverbases.FooResolvers
import viaduct.tenant.runtime.execution.invalidfragment.objectfragment.resolverbases.QueryResolvers

// Test for a object value fragment that is invalid
class InvalidObjectFragmentFeatureAppTest : InvalidObjectFragmentContractTest() {
    @Resolver("horse")
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
