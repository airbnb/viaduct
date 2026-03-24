package viaduct.tenant.runtime.execution.objectcontract

import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.objectcontract.resolverbases.FooResolvers
import viaduct.tenant.runtime.execution.objectcontract.resolverbases.NestedFooResolvers
import viaduct.tenant.runtime.execution.objectcontract.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.ObjectContractTest

class KotlinObjectContractTest : ObjectContractTest() {
    @Resolver
    class Query_GreetingResolver : QueryResolvers.Greeting() {
        override suspend fun resolve(ctx: Context) = Foo.Builder(ctx).build()
    }

    @Resolver
    class Foo_BazResolver : FooResolvers.Baz() {
        override suspend fun resolve(ctx: Context) = "world"
    }

    @Resolver
    class Foo_NestedResolver : FooResolvers.Nested() {
        override suspend fun resolve(ctx: Context) = NestedFoo.Builder(ctx).build()
    }

    @Resolver
    class NestedFoo_ValueResolver : NestedFooResolvers.Value() {
        override suspend fun resolve(ctx: Context) = "nested_value"
    }

    @Resolver("baz")
    class Foo_ShorthandBarResolver : FooResolvers.ShorthandBar() {
        override suspend fun resolve(ctx: Context) = ctx.objectValue.get<String>("baz", String::class)
    }

    @Resolver(
        """
        fragment _ on Foo {
            baz
            nested {
                value
            }
        }
        """
    )
    class Foo_FragmentBarResolver : FooResolvers.FragmentBar() {
        override suspend fun resolve(ctx: Context): String {
            val baz = ctx.objectValue.get<String>("baz", String::class)
            val nested = ctx.objectValue.get<NestedFoo>("nested", NestedFoo::class)
            return "$baz-${nested.getValue()}"
        }
    }

    @Resolver
    class Query_FooListResolver : QueryResolvers.FooList() {
        override suspend fun resolve(ctx: Context): List<Foo> {
            return listOf(
                Foo.Builder(ctx).build(),
                Foo.Builder(ctx).build(),
                Foo.Builder(ctx).build()
            )
        }
    }

    @Resolver
    class Query_NestedFooListResolver : QueryResolvers.NestedFooList() {
        override suspend fun resolve(ctx: Context): List<NestedFoo> {
            return listOf(
                NestedFoo.Builder(ctx).build(),
                NestedFoo.Builder(ctx).build()
            )
        }
    }

    @Resolver
    class Query_FooWithArgsResolver : QueryResolvers.FooWithArgs() {
        override suspend fun resolve(ctx: Context): Foo {
            ctx.arguments.message ?: "default message"
            ctx.arguments.count ?: 0
            return Foo.Builder(ctx).build()
        }
    }

    @Resolver
    class Foo_MessageResolver : FooResolvers.Message() {
        override suspend fun resolve(ctx: Context): String {
            return "message from resolver"
        }
    }
}
