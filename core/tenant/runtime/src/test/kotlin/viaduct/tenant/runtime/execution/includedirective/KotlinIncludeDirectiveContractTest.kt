@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.includedirective

import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.includedirective.resolverbases.FooResolvers
import viaduct.tenant.runtime.execution.includedirective.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.includedirective.resolverbases.ThrowerResolvers

class KotlinIncludeDirectiveContractTest : IncludeDirectiveContractTest() {
    @Resolver
    class Query_FooResolver : QueryResolvers.Foo() {
        override suspend fun resolve(ctx: Context): Foo {
            return Foo.Builder(ctx).build()
        }
    }

    @Resolver
    class Query_ThrowerResolver : QueryResolvers.Thrower() {
        override suspend fun resolve(ctx: Context): Thrower {
            return Thrower.Builder(ctx).build()
        }
    }

    @Resolver
    class Query_BooleanValueResolver : QueryResolvers.BooleanValue() {
        override suspend fun resolve(ctx: Context): Boolean {
            return false
        }
    }

    @Resolver
    class Foo_IntValueResolver : FooResolvers.IntValue() {
        override suspend fun resolve(ctx: Context): Int {
            return 10
        }
    }

    @Resolver
    class Foo_SValueResolver : FooResolvers.SValue() {
        override suspend fun resolve(ctx: Context): String {
            return "result value"
        }
    }

    @Resolver
    class Thrower_WillThrowResolver : ThrowerResolvers.WillThrow() {
        override suspend fun resolve(ctx: Context): Int {
            throw RuntimeException("asd")
        }
    }
}
