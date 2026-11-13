@file:OptIn(viaduct.apiannotations.InternalApi::class)

package viaduct.engine.runtime.execution

import graphql.ErrorType
import graphql.GraphQLError
import graphql.language.SourceLocation
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.graphql.scopes.SchemaScopingMode
import viaduct.graphql.scopes.SchemaView
import viaduct.graphql.scopes.ScopedSchemaBuilder
import viaduct.graphql.test.assertJson

interface OperationValidationContract : QueryExecutionContract {
    private fun operationValidationModule() =
        EngineTestModule(
            """
            directive @bypassPolicyCheck on FIELD

            extend type Query @scope(to: ["public","private"]) {
                f1: Int
            }

            extend type Query @scope(to: ["private"]) {
                f2: Int
            }
            """
        ) {
            fieldWithValue("Query" to "f1", 1)
            fieldWithValue("Query" to "f2", 2)
        }

    @Test
    fun `valid full schema query`() {
        operationValidationModule().runContractTest {
            runQuery("{ f1 f2 }")
                .assertJson("""{ "data": {"f1": 1, "f2": 2} }""")
        }
    }

    @Test
    fun `full schema accepts internal bypassPolicyCheck directive`() {
        operationValidationModule().runContractTest {
            runQuery("{ f1 @bypassPolicyCheck }")
                .assertJson("""{ "data": {"f1": 1} }""")
        }
    }

    @Test
    fun `client fragments accept repeatable metadata on definitions`() {
        EngineTestModule(
            """
            directive @metadata(id: String!) repeatable on FRAGMENT_DEFINITION
            extend type Query { source: Int }
            """.trimIndent(),
        ) {
            fieldWithValue("Query" to "source", 9)
        }.runContractTest {
            runQuery(
                """
                { ...Client }
                fragment Client on Query @metadata(id: "first") @metadata(id: "second") { ...Input }
                fragment Input on Query @metadata(id: "input") { result: source }
                """.trimIndent(),
            ).assertJson("{data: {result: 9}}")
        }
    }

    @ParameterizedTest
    @CsvSource(
        "@include(if: true), Client",
        "@include(if: true), Input",
        "@skip(if: false), Client",
        "@skip(if: false), Input",
    )
    fun `client fragments reject conditional directives on definitions`(
        directive: String,
        fragmentName: String,
    ) {
        val resolverCalls = AtomicInteger()
        EngineTestModule("extend type Query { source: Int }") {
            field("Query" to "source") {
                resolver {
                    fn { _, _, _, _, _ ->
                        resolverCalls.incrementAndGet()
                        9
                    }
                }
            }
        }.runContractTest {
            val result = runQuery(
                """
                { ...Client }
                fragment Client on Query ${if (fragmentName == "Client") directive else ""} { ...Input }
                fragment Input on Query ${if (fragmentName == "Input") directive else ""} { source }
                """.trimIndent(),
            )

            assertEquals(1, result.errors.size)
            assertEquals("ValidationError", result.errors.single().errorType.toString())
            assertFalse(result.isDataPresent)
            assertEquals(0, resolverCalls.get())
        }
    }

    @Test
    fun `invalid full schema query`() {
        operationValidationModule().runContractTest {
            val result = runQuery("{ f1 f2 f3 }")
            assertEquals(1, result.errors.size)
            assertTrue(result.errors[0].message.contains("FieldUndefined@[f3]"))
        }
    }

    @Test
    fun `invalid scoped schema query`() {
        val bootstrapper = operationValidationModule()
        val publicSchema = EngineSchema(
            ScopedSchemaBuilder(
                inputSchema = bootstrapper.fullSchema.schema,
                additionalVisitorConstructors = emptyList(),
                scopingMode = SchemaScopingMode.ScopeAware(setOf("public", "private")),
            ).build(SchemaView.Scoped(setOf("public"))).filtered
        )

        bootstrapper.runContractTest(schema = publicSchema) {
            val result = runQuery("{ f1 f2 }")
            assertEquals(1, result.errors.size)
            assertTrue(result.errors[0].message.contains("FieldUndefined@[f2]"))
        }
    }

    @Test
    fun `scoped schema rejects internal bypassPolicyCheck directive`() {
        val bootstrapper = operationValidationModule()
        val publicSchema = EngineSchema(
            ScopedSchemaBuilder(
                inputSchema = bootstrapper.fullSchema.schema,
                additionalVisitorConstructors = emptyList(),
                scopingMode = SchemaScopingMode.ScopeAware(setOf("public", "private")),
            ).build(SchemaView.Scoped(setOf("public"))).filtered
        )

        bootstrapper.runContractTest(schema = publicSchema) {
            val result = runQuery("{ f1 @bypassPolicyCheck }")
            assertEquals(1, result.errors.size)
            assertTrue(result.errors[0].message.contains("Unknown directive"))
        }
    }

    @Test
    fun `internal scoped schema accepts bypassPolicyCheck directive`() {
        val bootstrapper = operationValidationModule()
        val internalSchema = EngineSchema(
            ScopedSchemaBuilder(
                inputSchema = bootstrapper.fullSchema.schema,
                additionalVisitorConstructors = emptyList(),
                scopingMode = SchemaScopingMode.ScopeAware(setOf("public", "private")),
            ).buildScopedFull(setOf("public")).filtered
        )

        bootstrapper.runContractTest(schema = internalSchema) {
            runQuery("{ f1 @bypassPolicyCheck }")
                .assertJson("""{ "data": {"f1": 1} }""")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["inline", "fragment"])
    fun `defer accepts omitted null empty and nonempty string labels`(kind: String) {
        labelValidationModule().runContractTest {
            for (argument in listOf("", "(label: null)", "(label: \"\")", "(label: \"details\")")) {
                val query = "{ item { ${labelSelection(kind, argument)} } } ${labelFragment(kind)}"
                val result = runQuery(query)
                assertTrue(result.errors.isEmpty(), result.errors.toString())
                assertEquals(mapOf("item" to mapOf("value" to 1)), result.getData())
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["inline", "fragment"])
    fun `defer rejects variable labels even with a string default`(kind: String) {
        assertLabelValidationError(
            "query(${'$'}label: String = \"details\") { item { ${labelSelection(kind, "(label: ${'$'}label)")} } } ${labelFragment(kind)}",
            "static string",
        )
    }

    @ParameterizedTest
    @CsvSource("inline, 1", "fragment, INVALID", "inline, true")
    fun `defer rejects non-string label literals`(
        kind: String,
        label: String,
    ) {
        assertLabelValidationError(
            "{ item { ${labelSelection(kind, "(label: $label)")} } } ${labelFragment(kind)}",
            "static string",
            expectedErrorCount = 2,
        )
    }

    @ParameterizedTest
    @CsvSource("inline, inline", "fragment, fragment", "inline, fragment")
    fun `duplicate defer labels are rejected and identify both directives`(
        firstKind: String,
        secondKind: String,
    ) {
        val first = labelSelection(firstKind, "(label: \"same\")")
        val second = labelSelection(secondKind, "(label: \"same\")")
        val fragment = labelFragment(if (firstKind == "fragment" || secondKind == "fragment") "fragment" else "inline")
        val error = assertLabelValidationError(
            """
            {
                item {
                    $first
                    $second
                }
            }
            $fragment
            """.trimIndent(),
            "unique",
        )
        assertEquals(
            listOf(SourceLocation(3, first.indexOf('@') + 9), SourceLocation(4, second.indexOf('@') + 9)),
            error.locations,
        )
    }

    @Test
    fun `duplicate label locations follow document order across nested selections`() {
        val first = "... @defer { ... @defer(label: \"same\") { value } }"
        val second = "... @defer(label: \"same\") { value }"
        val error = assertLabelValidationError(
            """
            {
                item {
                    $first
                    $second
                }
            }
            """.trimIndent(),
            "unique",
        )
        assertEquals(
            listOf(SourceLocation(3, first.lastIndexOf('@') + 9), SourceLocation(4, second.indexOf('@') + 9)),
            error.locations,
        )
    }

    @Test
    fun `defer rejects duplicate labels across operations even when a directive is disabled`() {
        assertLabelValidationError(
            """
            query First { item { ... @defer(label: "same", if: false) { value } } }
            query Second { item { ... @defer(label: "same") { value } } }
            """.trimIndent(),
            "unique",
        )
    }

    @Test
    fun `duplicate defer labels are still rejected when another field fails validation`() {
        val calls = AtomicInteger()
        val result = executeQuery(
            labelValidationModule { calls.incrementAndGet() },
            """
            {
                item {
                    ... @defer(label: "same") { value }
                    ... @defer(label: "same") { value }
                }
                missingField @useExperimental
            }
            """.trimIndent(),
        )

        assertEquals(2, result.errors.size, result.errors.toString())
        assertTrue(result.errors.any { it.message.contains("FieldUndefined@[missingField]") }, result.errors.toString())
        assertTrue(result.errors.any { it.message.contains("unique") }, result.errors.toString())
        assertEquals(0, calls.get(), "Validation errors must not execute resolvers")
    }

    @Test
    fun `syntax errors are preserved when the document cannot be parsed`() {
        val result = executeQuery(labelValidationModule(), "{ item {")

        assertEquals(1, result.errors.size, result.errors.toString())
        assertEquals(ErrorType.InvalidSyntax.toString(), result.errors.single().errorType.toString())
    }

    @Test
    fun `spreading a fragment twice does not duplicate defer labels declared inside its definition`() {
        labelValidationModule().runContractTest {
            val result = runQuery(
                """
                { item { ...Details ...Details } }
                fragment Details on IncrementalItem { ... @defer(label: "details") { value } }
                """.trimIndent(),
            )
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(mapOf("item" to mapOf("value" to 1)), result.getData())
        }
    }

    @Test
    fun `defer allows distinct labels mixed with multiple unlabeled uses`() {
        labelValidationModule().runContractTest {
            runQuery(
                """
                { item {
                    ...One @defer(label: "one")
                    ...Two @defer(label: "two")
                    ... @defer { value }
                    ... @defer { value }
                } }
                fragment One on IncrementalItem { value }
                fragment Two on IncrementalItem { value }
                """.trimIndent(),
            ).assertJson("""{"data":{"item":{"value":1}}}""")
        }
    }

    private fun labelSelection(
        kind: String,
        argument: String
    ): String = if (kind == "inline") "... @defer$argument { value }" else "...Details @defer$argument"

    private fun labelFragment(kind: String): String = if (kind == "fragment") "fragment Details on IncrementalItem { value }" else ""

    private fun assertLabelValidationError(
        query: String,
        message: String,
        expectedErrorCount: Int = 1,
    ): GraphQLError {
        val calls = AtomicInteger()
        val result = executeQuery(labelValidationModule { calls.incrementAndGet() }, query)
        assertEquals(expectedErrorCount, result.errors.size, result.errors.toString())
        val error = result.errors.single { it.message.contains(message, ignoreCase = true) }
        assertEquals(ErrorType.ValidationError.toString(), error.errorType.toString())
        assertTrue(!error.locations.isNullOrEmpty(), error.toString())
        assertEquals(0, calls.get(), "Invalid labels must not execute resolvers")
        return error
    }

    private fun labelValidationModule(onResolve: () -> Unit = {}) =
        EngineTestModule(
            """
            directive @useExperimental on FIELD
            extend type Query { item: IncrementalItem }
            type IncrementalItem { value: Int }
            """.trimIndent(),
        ) {
            field("Query" to "item") {
                resolver {
                    fn { _, _, _, _, context ->
                        onResolve()
                        createEngineObjectData(
                            context.fullSchema.schema.getObjectType("IncrementalItem"),
                            mapOf("value" to 1),
                        )
                    }
                }
            }
        }
}
