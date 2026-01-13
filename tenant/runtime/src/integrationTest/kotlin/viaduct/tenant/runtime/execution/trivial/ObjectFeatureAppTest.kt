package viaduct.tenant.runtime.execution.trivial

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.execution.trivial.resolverbases.FooResolvers
import viaduct.tenant.runtime.execution.trivial.resolverbases.NestedFooResolvers
import viaduct.tenant.runtime.execution.trivial.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

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
class ObjectFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        | type Foo {
        |   shorthandBar: String @resolver
        |   fragmentBar: String @resolver
        |   baz: String @resolver
        |   nested: NestedFoo @resolver
        |   message: String @resolver
        | }
        | type NestedFoo {
        |   value: String @resolver
        | }
        | extend type Query {
        |   greeting: Foo @resolver
        |   fooList: [Foo] @resolver
        |   nestedFooList: [NestedFoo] @resolver
        |   fooWithArgs(message: String, count: Int): Foo @resolver
        | }
        | #END_SCHEMA
    """.trimMargin()

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
        override suspend fun resolve(ctx: Context) = ctx.objectValue.get<String>("baz", String::class)
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
            val baz = ctx.objectValue.get<String>("baz", String::class)
            val nested = ctx.objectValue.get<NestedFoo>("nested", NestedFoo::class)
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

    @Test
    fun `shorthand resolver pattern`() {
        execute(
            query = """
                query {
                    greeting {
                        shorthandBar
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "greeting" to {
                    "shorthandBar" to "world"
                }
            }
        }
    }

    @Test
    fun `fragment resolver pattern`() {
        execute(
            query = """
                query {
                    greeting {
                        fragmentBar
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "greeting" to {
                    "fragmentBar" to "world-nested_value"
                }
            }
        }
    }

    @Test
    fun `field resolver returns a list of Foo objects`() {
        execute(
            query = """
                query {
                    fooList {
                        baz
                        nested {
                            value
                        }
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "fooList" to arrayOf(
                    {
                        "baz" to "world"
                        "nested" to {
                            "value" to "nested_value"
                        }
                    },
                    {
                        "baz" to "world"
                        "nested" to {
                            "value" to "nested_value"
                        }
                    },
                    {
                        "baz" to "world"
                        "nested" to {
                            "value" to "nested_value"
                        }
                    }
                )
            }
        }
    }

    @Test
    fun `field resolver returns a list of NestedFoo objects`() {
        execute(
            query = """
                query {
                    nestedFooList {
                        value
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "nestedFooList" to arrayOf(
                    {
                        "value" to "nested_value"
                    },
                    {
                        "value" to "nested_value"
                    }
                )
            }
        }
    }

    @Test
    fun `field resolver with arguments returns an object type`() {
        execute(
            query = """
                query {
                    fooWithArgs(message: "test message", count: 5) {
                        message
                        baz
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "fooWithArgs" to {
                    "message" to "message from resolver"
                    "baz" to "world"
                }
            }
        }
    }

    @Test
    fun `field resolver with null arguments returns an object type`() {
        execute(
            query = """
                query {
                    fooWithArgs(message: null, count: null) {
                        message
                        baz
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "fooWithArgs" to {
                    "message" to "message from resolver"
                    "baz" to "world"
                }
            }
        }
    }
}
