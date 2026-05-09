@file:Suppress("unused", "ClassName", "PackageDirectoryMismatch")

package inludedirective.featurapps

import inludedirective.featurapps.resolverbases.FooResolvers
import inludedirective.featurapps.resolverbases.QueryResolvers
import inludedirective.featurapps.resolverbases.ThrowerResolvers
import viaduct.api.resolver.Resolver

class IncludeDirectiveFeatureAppTest : IncludeDirectiveContractTest() {
    // Tenant provided resolvers

    @Resolver
    class Query_FooResolver : QueryResolvers.Foo() {
        override suspend fun resolve(ctx: Context): Foo {
            return Foo.Builder(ctx).build()
        }
    }

    @Resolver
    class Query_BooleanResolver : QueryResolvers.BooleanValue() {
        override suspend fun resolve(ctx: Context): Boolean {
            return false
        }
    }

    @Resolver
    class Foo_IValueResolver : FooResolvers.IntValue() {
        override suspend fun resolve(ctx: Context): Int {
            return 10
        }
    }

    @Resolver
    class Query_ThrowerResolver : QueryResolvers.Thrower() {
        override suspend fun resolve(ctx: Context): Thrower {
            return Thrower.Builder(ctx).build()
        }
    }

    @Resolver
    class Thrower_WillThrowResolver : ThrowerResolvers.WillThrow() {
        override suspend fun resolve(ctx: Context): Int {
            throw RuntimeException("asd")
        }
    }

    @Resolver
    class Foo_SValueResolver : FooResolvers.SValue() {
        override suspend fun resolve(ctx: Context): String {
            return "result value"
        }
    }
}
