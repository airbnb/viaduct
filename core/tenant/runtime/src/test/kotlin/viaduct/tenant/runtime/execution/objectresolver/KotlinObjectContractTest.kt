package viaduct.tenant.runtime.execution.objectresolver

import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.objectresolver.resolverbases.FooResolvers
import viaduct.tenant.runtime.execution.objectresolver.resolverbases.NestedFooResolvers
import viaduct.tenant.runtime.execution.objectresolver.resolverbases.PersonResolvers
import viaduct.tenant.runtime.execution.objectresolver.resolverbases.QueryResolvers

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
        override suspend fun resolve(ctx: Context) = ctx.getObjectValue().get<String>("baz", String::class)
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
            val baz = ctx.getObjectValue().get<String>("baz", String::class)
            val nested = ctx.getObjectValue().get<NestedFoo>("nested", NestedFoo::class)
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

    @Resolver
    class Query_PersonByNameResolver : QueryResolvers.PersonByName() {
        override suspend fun resolve(ctx: Context): Person {
            val name = ctx.arguments.name
            val address = Address.Builder(ctx).street("123 Main St").city("San Francisco").country("USA").build()
            return Person.Builder(ctx).name(name).age(30).address(address).build()
        }
    }

    @Resolver(
        """
        fragment _ on Person {
            address { street city country }
        }
        """
    )
    class Person_FullAddressResolver : PersonResolvers.FullAddress() {
        override suspend fun resolve(ctx: Context): String {
            val address = ctx.getObjectValue().getAddress() ?: return "No address"
            return "${address.getStreet()}, ${address.getCity()}, ${address.getCountry()}"
        }
    }

    @Resolver
    class Person_GreetingResolver : PersonResolvers.Greeting() {
        override suspend fun resolve(ctx: Context): String = "Hello!"
    }
}
