package viaduct.tenant.runtime.featuretests

import com.google.inject.ProvisionException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import viaduct.api.context.FieldExecutionContext
import viaduct.api.types.Arguments
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.GraphQLBuildError
import viaduct.engine.api.VariableCycleException
import viaduct.engine.runtime.tenantloading.InvalidVariableException
import viaduct.engine.runtime.tenantloading.RequiredSelectionsCycleException
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestSchemaFixture
import viaduct.tenant.runtime.featuretests.fixtures.Foo
import viaduct.tenant.runtime.featuretests.fixtures.Query
import viaduct.tenant.runtime.featuretests.fixtures.Union
import viaduct.tenant.runtime.featuretests.fixtures.UntypedFieldContext
import viaduct.tenant.runtime.featuretests.fixtures.get
import viaduct.tenant.runtime.featuretests.fixtures.tryGet

@ExperimentalCoroutinesApi
class FromFieldVariablesResolverTest {
    @Test
    fun `from object field -- simple`() =
        FeatureTestBuilder("extend type Query { x:Int, y(b:Int):Int, z:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 5 },
                "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z"))
            )
            .resolver("Query" to "y") { it.arguments.get<Int>("b") * 3 }
            .resolver("Query" to "z") { 2 }
            .build()
            .assertJson("{data: {x: 30}}", "{x}")

    @Test
    fun `from object field -- variables used by field on non-root object`() =
        FeatureTestBuilder(
            """
                    type Obj { x:Int, y(b:Int):Int, z:Int }
                    extend type Query { obj:Obj }
            """.trimIndent(),
            useFakeGRTs = true,
        )
            .resolver(
                "Obj" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 5 },
                "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z"))
            )
            .resolver("Obj" to "y") { it.arguments.get<Int>("b") * 3 }
            .resolver("Obj" to "z") { 2 }
            .resolver("Query" to "obj") { emptyMap<String, Any?>() }
            .build()
            .assertJson("{data: {obj: {x: 30}}}", "{ obj { x } }")

    @Test
    fun `from object field -- simple mutation field`() =
        FeatureTestBuilder(
            """
                    extend type Mutation { x:Int, y(b:Int):Int, z:Int }
                    extend type Query { empty:Int }
            """.trimIndent(),
            useFakeGRTs = true,
        )
            .resolver(
                "Mutation" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 5 },
                "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z"))
            )
            .resolver("Mutation" to "y") { it.arguments.get<Int>("b") * 3 }
            .resolver("Mutation" to "z") { 2 }
            .build()
            .assertJson(
                "{data: {x: 30}}",
                "mutation { x }"
            )

    @Test
    fun `from object field -- selection is field with omitted arg and default value`() =
        FeatureTestBuilder("extend type Query { x:Int, y(b:Int):Int, z(c:Int = 2):Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 7 },
                "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z"))
            )
            .resolver("Query" to "y") { it.arguments.get<Int>("b") * 5 }
            .resolver("Query" to "z") { it.arguments.get<Int>("c") * 3 }
            .build()
            .assertJson("{data: {x: 210}}", "{ x }")

    @Test
    fun `from object field -- selection is field with arg`() =
        FeatureTestBuilder("extend type Query { x:Int!, y(b:Int!):Int!, z(c:Int!):Int! }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 7 },
                "y(b:\$b), z(c:2)",
                variables = listOf(FromObjectFieldVariable("b", "z"))
            )
            .resolver("Query" to "y") { it.arguments.get<Int>("b") * 5 }
            .resolver("Query" to "z") { it.arguments.get<Int>("c") * 3 }
            .build()
            .assertJson("{data: {x: 210}}", "{x}")

    @Test
    fun `from object field -- selection is field with omitted argument value`() =
        FeatureTestBuilder("extend type Query { x:Int, y(b:Int):Int, z(c:Int):Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 3 },
                "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z"))
            )
            .resolver("Query" to "y") { it.arguments.get<Int>("b") * 2 }
            .resolver("Query" to "z") { it.arguments.tryGet<Int>("c") ?: -1 }
            .build()
            .assertJson("{data: {x: -6}}", "{ x }")

    @Test
    fun `from object field -- selection is aliased`() =
        FeatureTestBuilder("extend type Query { x:Int, y(b:Int):Int, z:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 5 },
                "y(b:\$b), myz:z",
                variables = listOf(FromObjectFieldVariable("b", "myz"))
            )
            .resolver("Query" to "y") { it.arguments.get<Int>("b") * 3 }
            .resolver("Query" to "z") { 2 }
            .build()
            .assertJson("{data: {x: 30}}", "{x}")

    @Test
    fun `from object field -- selection is list-valued`() =
        FeatureTestBuilder("extend type Query { x:Int, y(b:[Int]):Int, z:[Int] }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 7 },
                "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z"))
            )
            .resolver("Query" to "y") { it.arguments.get<List<Int>>("b").fold(1) { acc, i -> acc * i } }
            .resolver("Query" to "z") { listOf(2, 3, 5) }
            .build()
            .assertJson("{data: {x: 210}}", "{x}")

    @Test
    fun `from object field -- single-field-multiple-variable -- multiple variables on required selection`() =
        FeatureTestBuilder("extend type Query { x:Int, y(b:Int, c:Int):Int, z:Int, w:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 7 },
                "y(b:\$b, c:\$c), z, w",
                variables = listOf(
                    FromObjectFieldVariable("b", "z"),
                    FromObjectFieldVariable("c", "w"),
                )
            )
            .resolver("Query" to "y") { it.arguments.get<Int>("b") * it.arguments.get<Int>("c") * 5 }
            .resolver("Query" to "z") { 3 }
            .resolver("Query" to "w") { 2 }
            .build()
            .assertJson("{data: {x: 210}}", "{x}")

    @Test
    fun `from object field -- single-field-multiple-variable -- multiple required selections with variables`() =
        FeatureTestBuilder("extend type Query { x:Int, y(b:Int):Int, z(c:Int):Int, w:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 7 },
                "y(b:\$b), z(c:\$c), w",
                variables = listOf(
                    FromObjectFieldVariable("b", "z"),
                    FromObjectFieldVariable("c", "w"),
                )
            )
            .resolver("Query" to "y") { it.arguments.get<Int>("b") * 5 }
            .resolver("Query" to "z") { it.arguments.get<Int>("c") * 3 }
            .resolver("Query" to "w") { 2 }
            .build()
            .assertJson("{data: {x: 210}}", "{x}")

    @Test
    fun `from object field -- selection traverses through object`() =
        FeatureTestBuilder(
            """
                    extend type Query { x:Int, y(b:Int):Int, z:Obj }
                    type Obj { w:Int }
            """.trimIndent(),
            useFakeGRTs = true,
        )
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 5 },
                "y(b:\$b), z { w }",
                variables = listOf(FromObjectFieldVariable("b", "z.w"))
            )
            .resolver("Query" to "y") { it.arguments.get<Int>("b") * 3 }
            .resolver("Query" to "z") { mapOf("w" to 2) }
            .build()
            .assertJson("{data: {x: 30}}", "{x}")

    @Test
    fun `from object field -- selection traverses through null object`() =
        FeatureTestBuilder(
            """
                    extend type Query { x:Int, y(b:Int):Int!, z:Obj }
                    type Obj { w:Int }
            """.trimIndent(),
            useFakeGRTs = true,
        )
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 5 },
                "y(b:\$b), z { w }",
                variables = listOf(FromObjectFieldVariable("b", "z.w"))
            )
            .resolver("Query" to "y") { (it.arguments.tryGet<Int>("x") ?: -1) * 3 }
            .resolver("Query" to "z") { null }
            .build()
            .assertJson("{data: {x: -15}}", "{ x }")

    @Test
    fun `from object field -- selection traverses through union`() {
        FeatureTestBuilder(FeatureTestSchemaFixture.sdl)
            .resolver(
                "Query" to "string1",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<String>("hasArgs2") + "." },
                "hasArgs2(x:\$x), iface { ... on Foo { value } }",
                variables = listOf(
                    FromObjectFieldVariable("x", "iface.value")
                )
            )
            .resolver("Query" to "hasArgs2") { it.arguments.get<String>("x") + "." }
            .resolver(
                "Query" to "iface",
                resolveFn = { ctx: FieldExecutionContext<Query, Query, Arguments.NoArguments, Union> ->
                    Foo.Builder(ctx).value("FOO").build()
                }
            )
            .let {
                val err = assertThrows<Exception> { it.build() }.stripWrappersAs<InvalidVariableException>()
                assertEquals("x", err.variableName)
            }
    }

    @Test
    fun `invalid from object field -- selection output type is not compatible with variable input type -- nullability mismatch`() {
        FeatureTestBuilder("extend type Query { x:Int, y(b:Int!):Int!, z:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { _: UntypedFieldContext -> 0 },
                "y(b:\$b), z",
                variables = listOf(
                    FromObjectFieldVariable("b", "z")
                )
            )
            .resolver("Query" to "y") { 0 }
            .resolver("Query" to "z") { null }
            .let {
                val err = assertThrows<Exception> { it.build() }.stripWrappersAs<InvalidVariableException>()
                assertEquals("b", err.variableName)
            }
    }

    @Test
    fun `invalid from object field -- selection output type is not compatible with variable input type -- type mismatch`() {
        FeatureTestBuilder("extend type Query { x:Int, y(b:Int!):Int!, z:String! }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { _: UntypedFieldContext -> 0 },
                "y(b:\$b), z",
                variables = listOf(
                    FromObjectFieldVariable("b", "z")
                )
            )
            .resolver("Query" to "y") { 0 }
            .resolver("Query" to "z") { "" }
            .let {
                val err = assertThrows<Exception> { it.build() }.stripWrappersAs<InvalidVariableException>()
                assertEquals("b", err.variableName)
            }
    }

    @Test
    fun `from object field - same variable name used in operation variable and annotation variable`() {
        FeatureTestBuilder("extend type Query { x(a:Int):Int, y(b:Int):Int, z:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext ->
                    ctx.arguments.get<Int>("a") * ctx.objectValue.get<Int>("y")
                },
                "y(b:\$b), z",
                variables = listOf(
                    FromObjectFieldVariable("b", "z")
                )
            )
            .resolver("Query" to "y") { it.arguments.get<Int>("b") * 5 }
            .resolver("Query" to "z") { 3 }
            .build()
            .assertJson(
                "{data: {x: 30}}",
                "query Q(\$vara:Int!) {x(a:\$vara)}",
                mapOf("vara" to 2)
            )
    }

    @Test
    fun `from object field - same variable name used in multiple selection sets`() {
        FeatureTestBuilder("extend type Query { x:Int, y:Int, z(c:Int):Int, w:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("z") * 5 },
                "z(c:\$var), w",
                variables = listOf(FromObjectFieldVariable("var", "w"))
            )
            .resolver(
                "Query" to "y",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("z") * 3 },
                "z(c:\$var), w",
                variables = listOf(FromObjectFieldVariable("var", "w"))
            )
            .resolver("Query" to "z") { 2 }
            .build()
            .assertJson(
                "{data: {x: 10, y: 6}}",
                "{x, y}",
            )
    }

    @Test
    fun `from object field -- variable used in conditional directive`() {
        var yResolved = false
        FeatureTestBuilder("extend type Query { x:String, y:Boolean, z:Boolean! }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext ->
                    val err = runCatching {
                        ctx.objectValue.get<Boolean>("y")
                    }
                    err.exceptionOrNull()?.javaClass?.simpleName
                },
                "y @skip(if:\$z), z",
                variables = listOf(FromObjectFieldVariable("z", "z"))
            )
            .resolver("Query" to "y") {
                yResolved = true
                true
            }
            .resolver("Query" to "z") { true }
            .build()
            .assertJson("{data: {x: \"ViaductTenantUsageException\"}}", "{x}")

        assertFalse(yResolved)
    }

    @Test
    fun `invalid from object field -- variable depends on a field in its own subselections`() {
        FeatureTestBuilder("extend type Query { x(a:Int):Query, y:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { _: UntypedFieldContext -> fail("should not execute") },
                "x(a:\$a) { y } ",
                variables = listOf(FromObjectFieldVariable("a", "x.y"))
            )
            .resolver("Query" to "y") { fail("should not execute") }
            .let {
                val err = assertThrows<Exception> { it.build() }.stripWrappers()
                assertTrue(err is VariableCycleException)
            }
    }

    @Test
    fun `invalid from object field -- variable selects a field that uses it`() {
        FeatureTestBuilder("extend type Query { x(a:Int):Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { _: UntypedFieldContext -> fail("should not execute") },
                "x(a:\$a)",
                variables = listOf(FromObjectFieldVariable("a", "x"))
            )
            .let {
                val err = assertThrows<Exception> { it.build() }.stripWrappers()
                assertTrue(err is VariableCycleException)
            }
    }

    @Test
    fun `invalid from object field -- deadlock between 2 variables -- same selection set`() {
        FeatureTestBuilder("extend type Query { x(a:Int):Int, y(b:Int):Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { _: UntypedFieldContext -> fail("should not execute") },
                "x(a:\$a), y(b:\$b)",
                variables = listOf(
                    FromObjectFieldVariable("a", "y"),
                    FromObjectFieldVariable("b", "x"),
                )
            )
            .resolver("Query" to "y") { fail("should not execute") }
            .let {
                val err = assertThrows<Exception> { it.build() }.stripWrappers()
                assertTrue(err is VariableCycleException)
            }
    }

    @Test
    fun `invalid from object field -- deadlock between 2 variables -- diff selection sets`() {
        FeatureTestBuilder("extend type Query { x(a:Int):Int, y(b:Int):Int, z:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { _: UntypedFieldContext -> fail("should not execute") },
                "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z"))
            )
            .resolver(
                "Query" to "y",
                { _: UntypedFieldContext -> fail("should not execute") },
                "x(a:\$a), z",
                variables = listOf(FromObjectFieldVariable("a", "z"))
            )
            .let {
                val err = assertThrows<Exception> { it.build() }.stripWrappers()
                assertTrue(err is RequiredSelectionsCycleException)
            }
    }

    @Test
    fun `invalid from query field -- path refers to missing selection`() =
        FeatureTestBuilder("extend type Query { x:Int, y(b:Int):Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { _: UntypedFieldContext -> fail("should not execute") },
                queryValueFragment = "y(b:\$b)",
                variables = listOf(FromQueryFieldVariable("b", "invalidField"))
            )
            .resolver("Query" to "y") { 2 }
            .let {
                val err = assertThrows<Exception> { it.build() }.stripWrappersAs<IllegalArgumentException>()
                assertTrue(err.message?.contains("No selections found for path") == true) { err.message }
            }

    @Test
    fun `invalid from query field -- path ends on object`() {
        FeatureTestBuilder("extend type Query { x:Int, y(b:Int):Int, z:Query, w:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { _: UntypedFieldContext -> fail("should not execute") },
                queryValueFragment = "y(b:\$b), z { w }",
                variables = listOf(FromQueryFieldVariable("b", "z"))
            )
            .resolver("Query" to "y") { fail("should not execute") }
            .resolver("Query" to "z") { fail("should not execute") }
            .let {
                val err = assertThrows<Exception> { it.build() }.stripWrappersAs<InvalidVariableException>()
                assertEquals("b", err.variableName)
            }
    }

    @Test
    fun `from query field -- simple`() =
        FeatureTestBuilder("extend type Query { x:Int, y(b:Int):Int, z:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.queryValue.get<Int>("y") * 5 },
                queryValueFragment = "y(b:\$b), z",
                variables = listOf(FromQueryFieldVariable("b", "z"))
            )
            .resolver("Query" to "y") { it.arguments.get<Int>("b") * 3 }
            .resolver("Query" to "z") { 2 }
            .build()
            .assertJson("{data: {x: 30}}", "{x}")

    @Test
    fun `from query field -- variables used by field on non-root object`() =
        FeatureTestBuilder(
            """
                    type Obj { x:Int }
                    extend type Query { obj:Obj y(b:Int):Int, z:Int }
            """.trimIndent(),
            useFakeGRTs = true,
        )
            .resolver(
                "Obj" to "x",
                { ctx: UntypedFieldContext -> ctx.queryValue.get<Int>("y") * 5 },
                queryValueFragment = "y(b:\$b), z",
                variables = listOf(FromQueryFieldVariable("b", "z"))
            )
            .resolver("Query" to "y") { it.arguments.get<Int>("b") * 3 }
            .resolver("Query" to "z") { 2 }
            .resolver("Query" to "obj") { emptyMap<String, Any?>() }
            .build()
            .assertJson("{data: {obj: {x: 30}}}", "{ obj { x } }")

    @Test
    fun `from query field -- simple mutation field`() =
        // Query.z = 2
        // Mutation.y(b) = $Query.z * 3
        // Mutation.x = $Mutation.y(b: $Query.z)) * 5
        // ->
        // Mutation.x = ($Query.z * 3) * 5
        // Mutation.x = (2 * 3) * 5
        // Mutation.x = 30
        FeatureTestBuilder(
            """
                    extend type Mutation { x:Int, y(b:Int):Int }
                    extend type Query { z:Int }
            """.trimIndent(),
            useFakeGRTs = true,
        )
            .resolver(
                "Mutation" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 5 },
                objectValueFragment = "y(b:\$z)",
                queryValueFragment = "z",
                variables = listOf(FromQueryFieldVariable("z", "z"))
            )
            .resolver("Mutation" to "y") { it.arguments.get<Int>("b") * 3 }
            .resolver("Query" to "z") { 2 }
            .build()
            .assertJson("{data: {x: 30}}", "mutation {x}")

    @Test
    fun `from query field -- binds variable to query field with different name`() =
        FeatureTestBuilder("extend type Query { x:Int, y(a:Int):Int, z:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.queryValue.get<Int>("y") * 5 },
                queryValueFragment = "y(a:\$varz), z",
                variables = listOf(FromQueryFieldVariable("varz", "z"))
            )
            .resolver("Query" to "y") { it.arguments.get<Int>("a") * 3 }
            .resolver("Query" to "z") { 2 }
            .build()
            .assertJson("{data: {x: 30}}", "{x}")

    @Test
    fun `from query field -- returns null value`() =
        FeatureTestBuilder("extend type Query { x:Int!, y(a:Int):Int!, z:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 2 },
                "y(a:\$z)",
                queryValueFragment = "z",
                variables = listOf(FromQueryFieldVariable("z", "z"))
            )
            .resolver("Query" to "y") { it.arguments.tryGet<Int>("a") ?: -1 }
            .resolver("Query" to "z") { null }
            .build()
            .assertJson("{data:{x:-2}}", "{x}")

    @Test
    fun `from query field -- single-field-multiple-variable -- multiple variables on required selection`() =
        FeatureTestBuilder("extend type Query { x:Int, y(b:Int, c:Int):Int, z:Int, w:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.queryValue.get<Int>("y") * 7 },
                queryValueFragment = "y(b:\$b, c:\$c), z, w",
                variables = listOf(
                    FromQueryFieldVariable("b", "z"),
                    FromQueryFieldVariable("c", "w")
                )
            )
            .resolver("Query" to "y") { it.arguments.get<Int>("b") * it.arguments.get<Int>("c") * 5 }
            .resolver("Query" to "z") { 3 }
            .resolver("Query" to "w") { 2 }
            .build()
            .assertJson("{data: {x: 210}}", "{x}")

    @Test
    fun `invalid from query field -- variable name overlaps with object field variable`() =
        FeatureTestBuilder("extend type Query { foo: String!, bar(x: String!): String! }", useFakeGRTs = true)
            .resolver(
                "Query" to "bar",
                { _: UntypedFieldContext -> "result" },
                "fragment _ on Query { foo }",
                queryValueFragment = "fragment _ on Query { foo }",
                variables = listOf(
                    FromObjectFieldVariable("name", "foo"),
                    FromQueryFieldVariable("name", "foo")
                )
            )
            .resolver("Query" to "foo") { "test" }
            .let {
                val err = assertThrows<Exception> { it.build() }.stripWrappersAs<IllegalArgumentException>()
                val msg = checkNotNull(err.message)
                assertTrue(msg.contains("unused variables")) { msg }
                assertTrue(msg.contains("name")) { msg }
            }

    @Test
    fun `invalid from query field -- variable name overlaps with argument variable`() =
        FeatureTestBuilder("extend type Query { foo: String!, bar(name: String!): String! }", useFakeGRTs = true)
            .resolver(
                "Query" to "bar",
                { _: UntypedFieldContext -> "result" },
                queryValueFragment = "fragment _ on Query { foo }",
                variables = listOf(
                    FromArgumentVariable("name", "name"),
                    FromQueryFieldVariable("name", "foo")
                )
            )
            .resolver("Query" to "foo") { "test" }
            .let {
                val err = assertThrows<Exception> { it.build() }.stripWrappersAs<IllegalArgumentException>()
                val msg = checkNotNull(err.message)
                assertTrue(msg.contains("unused variables")) { msg }
                assertTrue(msg.contains("name")) { msg }
            }

    @Test
    fun `mixed variables -- from query field and from argument`() =
        // Query.z = 3
        // Query.y(a,b) = $a * $b * 5
        // Query.x(a) = $Query.y(a:$a, $Query.z) * 7
        // ->
        // Query.x(2) = $Query.y(a:2, 3) * 7
        // Query.x(2) = 2 * 3 * 5 * 7
        // Query.x(2) = 210
        FeatureTestBuilder("extend type Query { x(a:Int):Int, y(a:Int, b:Int):Int, z:Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext ->
                    ctx.objectValue.get<Int>("y") * 7
                },
                objectValueFragment = "y(a:\$vara, b:\$varb)",
                queryValueFragment = "z",
                variables = listOf(
                    FromArgumentVariable("vara", "a"),
                    FromQueryFieldVariable("varb", "z")
                )
            )
            .resolver("Query" to "y") {
                it.arguments.get<Int>("a") * it.arguments.get<Int>("b") * 5
            }
            .resolver("Query" to "z") { 3 }
            .build()
            .assertJson("{data: {x: 210}}", "{x(a:2)}")

    @Test
    fun `mixed variables -- from query field and from object field`() =
        // Query.z(w) = $w * 2
        // Query.y(a,b) = $a * $b * 3
        // Query.x = $Query.y(a: $Query.z(w:7), b: $Query.z(w:5) * 11
        // ->
        // Query.x = $Query.y(a:14, b:10) * 11
        // Query.x = (14 * 10 * 3) * 11
        // Query.x = 4620
        FeatureTestBuilder("extend type Query { x:Int, y(a:Int, b:Int):Int, z(w:Int):Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: UntypedFieldContext -> ctx.objectValue.get<Int>("y") * 11 },
                "y(a:\$a, b:\$b), z1:z(w:7)",
                queryValueFragment = "z2:z(w:5)",
                variables = listOf(
                    FromObjectFieldVariable("a", "z1"),
                    FromQueryFieldVariable("b", "z2")
                )
            )
            .resolver("Query" to "y") {
                it.arguments.get<Int>("a") * it.arguments.get<Int>("b") * 3
            }
            .resolver("Query" to "z") { it.arguments.get<Int>("w") * 2 }
            .build()
            .assertJson("{data: {x: 4620}}", "{x}")
}

internal fun Throwable.stripWrappers(): Throwable =
    when (this) {
        is GraphQLBuildError -> this.cause!!.stripWrappers()
        is ProvisionException -> this.cause!!.stripWrappers()
        else -> this
    }

internal inline fun <reified T : Throwable> Throwable.stripWrappersAs(): T {
    val stripped = this.stripWrappers()
    return stripped as? T
        ?: throw IllegalStateException("Expected ${T::class.simpleName} but got ${stripped::class.simpleName}")
}
