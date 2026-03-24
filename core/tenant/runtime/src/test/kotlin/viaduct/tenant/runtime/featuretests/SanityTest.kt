package viaduct.tenant.runtime.featuretests

import graphql.ExecutionResultImpl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.api.FieldValue
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.graphql.test.assertJson
import viaduct.tenant.runtime.FakeArguments
import viaduct.tenant.runtime.FakeObject
import viaduct.tenant.runtime.FakeQuery
import viaduct.tenant.runtime.featuretests.fixtures.Baz
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder
import viaduct.tenant.runtime.featuretests.fixtures.Foo
import viaduct.tenant.runtime.featuretests.fixtures.Query
import viaduct.tenant.runtime.featuretests.fixtures.Query_HasArgs1_Arguments
import viaduct.tenant.runtime.featuretests.fixtures.UntypedFieldContext
import viaduct.tenant.runtime.featuretests.fixtures.get

/** tests to ensure that [FeatureTest] behaves as expected */
@ExperimentalCoroutinesApi
@Suppress("USELESS_IS_CHECK")
class SanityTest {
    @Test
    fun `resolver uses an implicit UntypedFieldContext`() =
        FeatureTestBuilder("extend type Query { x: Int }")
            .resolver("Query" to "x") { 42 }
            .build()
            .assertJson("{data: {x: 42}}", "{x}")

    @Test
    fun `resolver uses an explicit UntypedFieldContext`() =
        FeatureTestBuilder("extend type Query { x: Int }")
            .resolver("Query" to "x") { _: UntypedFieldContext -> 42 }
            .build()
            .assertJson("{data: {x: 42}}", "{x}")

    @Test
    fun `resolver uses an explicit FieldExecutionContext`() =
        FeatureTestBuilder("extend type Query { x: Int }")
            .resolver("Query" to "x") { _: FieldExecutionContext<*, *, *, *> -> 42 }
            .build()
            .assertJson("{data: {x: 42}}", "{x}")

    @Test
    fun `resolver accesses parent object via explicit grt`() =
        FeatureTestBuilder("extend type Query { x: Int }")
            .resolver(
                "Query" to "x",
                { ctx: FieldExecutionContext<Query, Query, Arguments, CompositeOutput.NotComposite> ->
                    assertTrue(ctx.objectValue is Query)
                    42
                }
            )
            .build()
            .assertJson("{data: {x: 42}}", "{x}")

    @Test
    fun `resolver accesses parent object via explicit FakeObject`() =
        FeatureTestBuilder("extend type Query { x: Int }", useFakeGRTs = true)
            .resolver(
                "Query" to "x",
                { ctx: FieldExecutionContext<FakeObject, FakeQuery, Arguments, CompositeOutput.NotComposite> ->
                    assertTrue(ctx.objectValue is FakeObject)
                    42
                }
            )
            .build()
            .assertJson("{data: {x: 42}}", "{x}")

    @Test
    fun `resolver accesses parent object via implicit FakeObject`() =
        FeatureTestBuilder("extend type Query { x: Int }", useFakeGRTs = true)
            .resolver("Query" to "x") { ctx ->
                assertTrue(ctx.objectValue is FakeObject)
                42
            }
            .build()
            .assertJson("{data: {x: 42}}", "{x}")

    @Test
    fun `resolver accesses arguments via explicit grt`() =
        FeatureTestBuilder("extend type Query { hasArgs1(x: Int!): Int! }")
            .resolver(
                "Query" to "hasArgs1",
                { ctx: FieldExecutionContext<Query, Query, Query_HasArgs1_Arguments, CompositeOutput> ->
                    ctx.arguments.x
                }
            )
            .build()
            .assertJson("{data: {hasArgs1: 42}}", "{hasArgs1(x: 42)}")

    @Test
    fun `resolver accesses arguments via explicit FakeArguments`() =
        FeatureTestBuilder("extend type Query { hasArgs1(x: Int!): Int! }", useFakeGRTs = true)
            .resolver(
                "Query" to "hasArgs1",
                { ctx: FieldExecutionContext<Query, Query, FakeArguments, CompositeOutput> ->
                    ctx.arguments.get<Int>("x")
                }
            )
            .build()
            .assertJson("{data: {hasArgs1: 42}}", "{hasArgs1(x: 42)}")

    @Test
    fun `resolver accesses arguments via implicit FakeArguments`() =
        FeatureTestBuilder("extend type Query { hasArgs1(x: Int!): Int! }", useFakeGRTs = true)
            .resolver("Query" to "hasArgs1") { it.arguments.get<Int>("x") }
            .build()
            .assertJson("{data: {hasArgs1: 42}}", "{hasArgs1(x: 42)}")

    @Test
    fun `resolver accesses selections via explicit grt`() {
        FeatureTestBuilder(
            """
                extend type Query { foo: Foo }
                type Foo { value: String }
            """.trimIndent()
        )
            .resolver(
                "Query" to "foo",
                { ctx: FieldExecutionContext<Query, Query, Arguments, Foo> ->
                    assertTrue(ctx.selections().contains(Foo.Reflection.Fields.value))
                    null
                }
            )
            .build()
            .assertJson("{data: {foo: null}}", "{foo {value}}")
    }

    @Test
    fun `nodeResolver uses an implicit UntypedNodeContext`() =
        FeatureTestBuilder(
            """
                type Baz { id: ID! }
                extend type Query { baz: Baz }
            """.trimIndent()
        )
            .resolver("Query" to "baz") { it.nodeFor(it.globalIDFor(Baz.Reflection, "")) }
            .nodeResolver("Baz") { ctx ->
                Baz.Builder(ctx).build()
            }
            .build()
            .assertJson("{data: {baz: {__typename: \"Baz\"}}}", "{baz {__typename}}")

    @Test
    fun `nodeResolver uses an explicit NodeExecutionContext`() =
        FeatureTestBuilder(
            """
                type Baz { id: ID! }
                extend type Query { baz: Baz }
            """.trimIndent()
        )
            .resolver("Query" to "baz") { it.nodeFor(it.globalIDFor(Baz.Reflection, "")) }
            .nodeResolver("Baz") { ctx: NodeExecutionContext<Baz> ->
                Baz.Builder(ctx).build()
            }
            .build()
            .assertJson("{data: {baz: {__typename: \"Baz\"}}}", "{baz {__typename}}")

    @Test
    fun `nodeBatchResolver uses an implicit UntypedNodeContext`() =
        FeatureTestBuilder(
            """
                type Baz { id: ID! }
                extend type Query { baz: Baz }
            """.trimIndent()
        )
            .resolver("Query" to "baz") { it.nodeFor(it.globalIDFor(Baz.Reflection, "")) }
            .nodeBatchResolver("Baz") { ctxs ->
                ctxs.map { ctx -> FieldValue.ofValue(Baz.Builder(ctx).build()) }
            }
            .build()
            .execute("{baz {__typename}}")
            .assertJson("{data: {baz: {__typename: \"Baz\"}}}")

    @Test
    fun `nodeBatchResolver uses an explicit NodeExecutionContext`() =
        FeatureTestBuilder(
            """
                type Baz { id: ID! }
                extend type Query { baz: Baz }
            """.trimIndent()
        )
            .resolver("Query" to "baz") { it.nodeFor(it.globalIDFor(Baz.Reflection, "")) }
            .nodeBatchResolver("Baz") { ctxs: List<NodeExecutionContext<Baz>> ->
                ctxs.map { ctx -> FieldValue.ofValue(Baz.Builder(ctx).build()) }
            }
            .build()
            .execute("{baz {__typename}}")
            .assertJson("{data: {baz: {__typename: \"Baz\"}}}")

    @Test
    fun `ExecutionResult_assertJson -- unparseable`() {
        val result = ExecutionResultImpl(emptyMap<String, Any?>(), emptyList())
        assertThrows<IllegalArgumentException> {
            result.assertJson("{")
        }
    }

    @Test
    fun `ExecutionResult_assertJson -- does not match`() {
        val result = ExecutionResultImpl(emptyMap<String, Any?>(), emptyList())
        val err = runCatching {
            result.assertJson("""{"x": 42}""")
        }.exceptionOrNull()
        // kotlin.test.assertEquals with JUnit 5 throws AssertionFailedError
        assertEquals("org.opentest4j.AssertionFailedError", err?.javaClass?.name)
    }

    @Test
    fun `ExecutionResult_assertJson -- match`() {
        val result = ExecutionResultImpl(mapOf("x" to 42), emptyList())
        assertDoesNotThrow {
            result.assertJson("""{"data": {"x": 42}}""")
        }
    }

    @Test
    fun `ExecutionResult_assertJson -- quality-of-life extras`() {
        val result = ExecutionResultImpl(mapOf("x" to 42), emptyList())
        // single-quoted keys
        result.assertJson("""{'data': {'x': 42}}""")

        // unquoted keys
        result.assertJson("""{data: {x: 42}}""")

        // with block comments
        result.assertJson(
            """
                {
                  /* test comment */
                  "data": {
                      "x": 42
                  }
                }
            """.trimIndent()
        )

        // with line comments
        result.assertJson(
            """
                {
                  // test comment
                  "data": {
                      "x": 42
                  }
                }
            """.trimIndent()
        )

        // trailing comma
        result.assertJson("""{"data": {"x": 42,},}""")
    }
}
