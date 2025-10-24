package viaduct.tenant.runtime.featuretests

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.GraphQLBuildError
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.UntypedFieldContext
import viaduct.tenant.runtime.featuretests.fixtures.const
import viaduct.tenant.runtime.featuretests.fixtures.get
import viaduct.tenant.runtime.featuretests.fixtures.tryGet
import viaduct.tenant.runtime.internal.VariablesProviderInfo

@ExperimentalCoroutinesApi
class VariablesResolverTest {
    @Test
    fun `variables provider -- variable name overlaps with bound field arg`() =
        FeatureTestBuilder("extend type Query { foo(x:Int): Int!, bar(x:Int!): Int! }", useFakeGRTs = true)
            .resolver(
                "Query" to "foo",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("bar") * 5 },
                "bar(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "x")),
                variablesProvider = VariablesProviderInfo.const(mapOf("x" to 2)),
            )
            .resolver("Query" to "bar") { it.arguments.get<Int>("x") * 3 }
            .let {
                assertThrows<Exception> {
                    it.build()
                }
                Unit
            }

    @Test
    fun `variables provider -- uses an arg variable with the same name as an unbound argument`() =
        FeatureTestBuilder("extend type Query { foo(x:Int):Int, bar(x:Int):Int, baz(x:Int):Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "foo",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("baz") * 11 },
                "baz(x:\$x)",
                variablesProvider = VariablesProviderInfo.const(mapOf("x" to 2))
            )
            .resolver(
                "Query" to "bar",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("baz") * 7 },
                "baz(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "x"))
            )
            .resolver("Query" to "baz") { it.arguments.get<Int>("x") * 5 }
            .build()
            .assertJson("{data:{foo:110, bar:105}}", "{foo(x:2) bar(x:3)}")

    @Test
    fun `from arg -- simple`() =
        FeatureTestBuilder("extend type Query { foo(y: Int!): Int!, bar(x:Int!): Int! }", useFakeGRTs = true)
            .resolver(
                "Query" to "foo",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("bar") * 5 },
                "bar(x:\$y)",
                variables = listOf(
                    FromArgumentVariable("y", "y")
                )
            )
            .resolver("Query" to "bar") { it.arguments.get<Int>("x") * 3 }
            .build()
            .assertJson("{data: {foo: 30}}", "{foo(y:2)}")

    @Test
    fun `from arg -- binds argument to variable with a different name`() =
        FeatureTestBuilder("extend type Query { foo(y: Int!): Int!, bar(x:Int!): Int! }", useFakeGRTs = true)
            .resolver(
                "Query" to "foo",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("bar") * 5 },
                "bar(x:\$vary)",
                variables = listOf(
                    FromArgumentVariable("vary", "y")
                )
            )
            .resolver("Query" to "bar") { it.arguments.get<Int>("x") * 3 }
            .build()
            .assertJson("{data: {foo: 30}}", "{foo(y:2)}")

    @Test
    fun `from arg -- path`() =
        FeatureTestBuilder(
            """
                    input Inp { x:Int! }
                    extend type Query { foo(inp:Inp!): Int!, bar(x:Int!):Int! }
            """.trimIndent(),
            useFakeGRTs = true,
        )
            .resolver(
                "Query" to "foo",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("bar") * 5 },
                "bar(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "inp.x"))
            )
            .resolver("Query" to "bar") { it.arguments.get<Int>("x") * 3 }
            .build()
            .assertJson("{data: {foo: 30}}", "{ foo(inp:{x:2}) }")

    @Test
    fun `from arg -- empty path`() =
        FeatureTestBuilder(
            """
                    input Inp { x:Int }
                    extend type Query { foo(x:Int!): Int!, bar(inp:Inp!):Int! }
            """.trimIndent(),
            useFakeGRTs = true,
        )
            .resolver(
                "Query" to "foo",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("bar") * 5 },
                "bar(x:\$x)",
                variables = listOf(FromArgumentVariable("inp", ""))
            )
            .resolver("Query" to "bar") { it.arguments.get<Int>("x") * 3 }
            .let {
                assertThrows<Exception> {
                    it.build()
                }
                Unit
            }

    @Test
    fun `from arg -- arg has default value`() =
        FeatureTestBuilder("extend type Query { foo(x:Int!=2):Int!, bar(x:Int):Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "foo",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("bar") * 5 },
                "bar(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "x"))
            )
            .resolver("Query" to "bar") { it.arguments.get<Int>("x") * 3 }
            .build()
            .let {
                // querying without an argument value will use the argument default
                it.assertJson("{data: {foo: 30}}", "{foo}")

                // querying with an argument value will use the provided value
                it.assertJson("{data: {foo: 45}}", "{foo(x:3)}")
            }

    @Test
    fun `from arg -- input field has default value`() =
        FeatureTestBuilder(
            """
                    input Inp { x:Int!=2 }
                    extend type Query { foo(inp:Inp!):Int!, bar(x:Int!):Int! }
            """.trimIndent(),
            useFakeGRTs = true,
        )
            .resolver(
                "Query" to "foo",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("bar") * 5 },
                "bar(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "inp.x"))
            )
            .resolver("Query" to "bar") { it.arguments.get<Int>("x") * 3 }
            .build()
            .let {
                // querying with an empty input object will use the field default
                it.assertJson("{data: {foo: 30}}", "{foo(inp:{})}")

                // querying with a populated object value will use the provided value
                it.assertJson("{data: {foo: 45}}", "{foo(inp:{x:3})}")
            }

    @Test
    fun `from arg -- path traverses through null object`() =
        FeatureTestBuilder(
            """
                    input Inp { x:Int!=2 }
                    extend type Query { foo(inp:Inp):Int, bar(x:Int):Int }
            """.trimIndent(),
            useFakeGRTs = true,
        )
            .resolver(
                "Query" to "foo",
                { ctx: UntypedFieldContext ->
                    ctx.objectValue.get<Int?>("bar")
                        ?.let { it * 5 }
                        ?: 7
                },
                "bar(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "inp.x"))
            )
            .resolver("Query" to "bar") {
                it.arguments.tryGet<Int>("x")?.let { arg -> arg * 3 }
            }
            .build()
            // querying with null inp will fail traversal into Inp.x
            .assertJson("{data: {foo: 7}}", "{foo(inp:null)}")

    @Test
    fun `from arg -- variable name overlaps with bound selection arg`() =
        FeatureTestBuilder("extend type Query { foo(x:Int):Int!, bar(x:Int!):Int!, baz:Int! }", useFakeGRTs = true)
            .resolver(
                "Query" to "foo",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("bar") * 5 },
                "bar(x:\$x), baz",
                variables = listOf(
                    FromArgumentVariable("x", "x"),
                    FromObjectFieldVariable("x", "baz"),
                ),
            )
            .resolver("Query" to "bar") { it.arguments.get<Int>("x") * 3 }
            .let {
                assertThrows<Exception> {
                    it.build()
                }
                Unit
            }

    @Test
    fun `from arg -- type validation`() =
        // this test case tries to use a nullable value, Query.foo(x:), in a position
        // where a non-nullable one is required, Query.bar(x:)
        FeatureTestBuilder("extend type Query { foo(x:Int):Int!, bar(x:Int!):Int! }", useFakeGRTs = true)
            .resolver(
                "Query" to "foo",
                { _: UntypedFieldContext -> 5 },
                "bar(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "x"))
            )
            .resolver("Query" to "bar") { 3 }
            .let {
                assertThrows<GraphQLBuildError> {
                    it.build()
                }
                Unit
            }

    @Test
    fun `from arg -- arg from operation variable`() =
        FeatureTestBuilder("extend type Query { foo(y:Int!):Int!, bar(x:Int!): Int! }", useFakeGRTs = true)
            .resolver(
                "Query" to "foo",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("bar") * 5 },
                "bar(x:\$y)",
                variables = listOf(
                    FromArgumentVariable("y", "y")
                )
            )
            .resolver("Query" to "bar") { it.arguments.get<Int>("x") * 3 }
            .build()
            .assertJson(
                "{data: {foo: 30}}",
                "query Q(\$vary:Int!) {foo(y:\$vary)}",
                mapOf("vary" to 2)
            )

    @Test
    fun `same variable name used in same argument with different values`() =
        // The spirit of this test is that baz is queried multiple times with "x:$x", but
        // with different values of $x. We want to test that we are not memoizing bar on the
        // reference to variable $x, but rather the value of variable $x
        FeatureTestBuilder("extend type Query { foo(x:Int):Int, bar(x:Int):Int, baz(x:Int):Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "foo",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("baz") * 11 },
                "baz(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "x"))
            )
            .resolver(
                "Query" to "bar",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("baz") * 7 },
                "baz(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "x"))
            )
            .resolver("Query" to "baz") { it.arguments.get<Int>("x") * 5 }
            .build()
            .assertJson("{data:{foo:110, bar:105}}", "{foo(x:2) bar(x:3)}")

    @Test
    fun `invalid variable reference`() =
        FeatureTestBuilder("extend type Query { foo: Int!, bar(x:Int!): Int! }", useFakeGRTs = true)
            .resolver(
                "Query" to "foo",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("bar") * 5 },
                "bar(x:\$invalid)",
            )
            .resolver("Query" to "bar") { it.arguments.get<Int>("x") * 3 }
            .let {
                assertThrows<Exception> { it.build() }
                Unit
            }
}
