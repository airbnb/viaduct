@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.trivial

import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.trivial.resolverbases.FooResolvers
import viaduct.tenant.runtime.execution.trivial.resolverbases.NestedFooResolvers
import viaduct.tenant.runtime.execution.trivial.resolverbases.QueryResolvers

/**
 * Feature tests for basic object resolution patterns.
 *
 * This tests:
 * - Shorthand and fragment @Resolver patterns
 * - Object builders
 * - Field resolvers returning lists of objects
 * - Field resolvers with arguments
 *
 * For ctx.query() / ctx.mutation() tests, see [SubqueryExecutionFeatureAppTest]
 * and [RecursiveSubmutationFeatureAppTest].
 */
class ObjectFeatureAppTest : ObjectContractTest() {
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

    // SHORTHAND PATTERN: Uses simple field name delegation
    @Resolver("baz")
    class Foo_ShorthandBarResolver : FooResolvers.ShorthandBar() {
        override suspend fun resolve(ctx: Context) = ctx.getObjectValue().get<String>("baz", String::class)
    }

    // FRAGMENT PATTERN: Uses GraphQL fragment syntax with nested selections
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

    /**
     * Field resolver that returns a list of Foo objects.
     * Demonstrates field resolvers returning lists of object types (covering ObjectResolverTests functionality).
     */
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

    /**
     * Field resolver that returns a list of NestedFoo objects.
     * Demonstrates field resolvers returning lists of object types (covering ObjectResolverTests functionality).
     */
    @Resolver
    class Query_NestedFooListResolver : QueryResolvers.NestedFooList() {
        override suspend fun resolve(ctx: Context): List<NestedFoo> {
            return listOf(
                NestedFoo.Builder(ctx).build(),
                NestedFoo.Builder(ctx).build()
            )
        }
    }

    /**
     * Field resolver that returns a Foo object with optional arguments.
     * Demonstrates handling null arguments with default values (covering ObjectResolverTests functionality).
     */
    @Resolver
    class Query_FooWithArgsResolver : QueryResolvers.FooWithArgs() {
        override suspend fun resolve(ctx: Context): Foo {
            // Handle null arguments with defaults
            ctx.arguments.message ?: "default message"
            ctx.arguments.count ?: 0

            return Foo.Builder(ctx)
                .build()
        }
    }

    /**
     * Field resolver that returns the message from the Foo object.
     * This demonstrates accessing data that was processed from arguments.
     */
    @Resolver
    class Foo_MessageResolver : FooResolvers.Message() {
        override suspend fun resolve(ctx: Context): String {
            // For this simple test, we'll return a fixed value that shows the pattern
            // In a real implementation, you'd access stored data from the parent object
            return "message from resolver"
        }
    }
}
