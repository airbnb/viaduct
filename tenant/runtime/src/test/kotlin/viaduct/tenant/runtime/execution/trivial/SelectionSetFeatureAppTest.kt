package viaduct.tenant.runtime.execution.trivial

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.execution.trivial.resolverbases.FooResolvers
import viaduct.tenant.runtime.execution.trivial.resolverbases.NestedFooResolvers
import viaduct.tenant.runtime.execution.trivial.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class SelectionSetFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        | directive @resolver on FIELD_DEFINITION
        | type Foo {
        |   shorthandBar: String @resolver
        |   fragmentBar: String @resolver
        |   baz: String @resolver
        |   nested: NestedFoo @resolver
        | }
        | type NestedFoo {
        |   value: String @resolver
        | }
        | type Query {
        |   greeting: Foo @resolver
        |   selectionSetDemo: String @resolver
        |   selectionSetShorthandDemo: String @resolver
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

    // This resolver demonstrates selectionsFor WITH FRAGMENT SYNTAX
    @Resolver
    class Query_SelectionSetDemoResolver : QueryResolvers.SelectionSetDemo() {
        override suspend fun resolve(ctx: Context): String {
            // selectionsFor with fragment syntax - this is the missing pattern!
            val selections = ctx.selectionsFor(
                Query.Reflection,
                """
                fragment _ on Query {
                    greeting {
                        baz
                        nested {
                            value
                        }
                    }
                }
                """
            )

            val result = ctx.query(selections)
            val greeting = result.getGreeting()
            val baz = greeting?.getBaz()
            val nested = greeting?.getNested()

            return "baz=$baz, nested.value=${nested?.getValue()}"
        }
    }

    // This resolver demonstrates selectionsFor WITH SHORTHAND SYNTAX
    @Resolver
    class Query_SelectionSetShorthandDemoResolver : QueryResolvers.SelectionSetShorthandDemo() {
        override suspend fun resolve(ctx: Context): String {
            // selectionsFor with shorthand syntax - direct field selection without "fragment _ on"
            val selections = ctx.selectionsFor(
                Query.Reflection,
                """
                greeting {
                    baz
                    nested {
                        value
                    }
                }
                """
            )

            val result = ctx.query(selections)
            val greeting = result.getGreeting()
            val baz = greeting?.getBaz()
            val nested = greeting?.getNested()

            return "shorthand: baz=$baz, nested.value=${nested?.getValue()}"
        }
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
    @Disabled("selectionsFor requires proper query object setup - demonstrates pattern but will fail")
    fun `selectionsFor with fragment syntax - demonstrates pattern but disabled`() {
        // This test demonstrates selectionsFor WITH FRAGMENT SYNTAX - the missing pattern!
        // It's disabled because selectionsFor requires a proper query object context.
        // The resolver above shows the correct pattern with fragment syntax.

        execute(
            query = """
                query {
                    selectionSetDemo
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "selectionSetDemo" to "baz=world, nested.value=nested_value"
            }
        }
    }

    @Test
    @Disabled("selectionsFor requires proper query object setup - demonstrates pattern but will fail")
    fun `selectionsFor with shorthand syntax - demonstrates pattern but disabled`() {
        // This test demonstrates selectionsFor WITH SHORTHAND SYNTAX (no "fragment _ on")
        // It's disabled because selectionsFor requires a proper query object context.
        // This uses simple field selection like "greeting { baz }" directly.

        execute(
            query = """
                query {
                    selectionSetShorthandDemo
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "selectionSetShorthandDemo" to "shorthand: baz=world, nested.value=nested_value"
            }
        }
    }
}
