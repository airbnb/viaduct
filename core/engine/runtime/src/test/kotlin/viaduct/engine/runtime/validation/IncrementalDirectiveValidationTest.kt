package viaduct.engine.runtime.validation

import graphql.ExecutionInput
import graphql.execution.preparsed.NoOpPreparsedDocumentProvider
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.language.SourceLocation
import graphql.parser.Parser
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.validation.ValidationError
import graphql.validation.ValidationErrorType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.api.EngineSchema

class IncrementalDirectiveValidationTest {
    private val schema = EngineSchema(
        UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(
                """
                directive @namespaceType on OBJECT
                type Query { item: IncrementalItem, shared: Shared }
                type Mutation { item: IncrementalItem, admin: Admin, admins: [Admin!]!, shared: Shared }
                type Admin @namespaceType { item: IncrementalItem, billing: Billing }
                type Billing @namespaceType { item: IncrementalItem }
                type Shared @namespaceType { item: IncrementalItem }
                type IncrementalItem { value: Int }
                """.trimIndent(),
            ),
        ),
    )
    private val provider = IncrementalDirectiveValidation(NoOpPreparsedDocumentProvider.INSTANCE, schema)

    @ParameterizedTest
    @ValueSource(strings = ["inline", "fragment"])
    fun `label errors on inline fragments and fragment spreads use the containing field path`(kind: String) {
        val selection = if (kind == "inline") "... @defer(label: ${'$'}label) { value }" else "...Details @defer(label: ${'$'}label)"
        val fragment = if (kind == "fragment") "fragment Details on IncrementalItem { value }" else ""
        val error = validationError(
            "query(${'$'}label: String = \"details\") { item { $selection } } $fragment",
        )

        assertEquals(listOf("item"), error.queryPath)
    }

    @Test
    fun `label errors use field names through nested fields and aliases`() {
        val error = validationError(
            """
            query Named(${'$'}label: String = "details") {
                renamedItem: item {
                    renamedChild: child {
                        ... @defer(label: ${'$'}label) { value }
                    }
                }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("item", "child"), error.queryPath)
    }

    @Test
    fun `label errors in a reused fragment definition include the fragment name once`() {
        val error = validationError(
            """
            query(${'$'}label: String = "details") { item { ...Details ...Details } }
            fragment Details on IncrementalItem {
                child { ... @defer(label: ${'$'}label) { value } }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Details", "child"), error.queryPath)
    }

    @Test
    fun `label errors at the query root have no field path`() {
        val error = validationError(
            """
            query(${'$'}label: String = "details") {
                ... @defer(label: ${'$'}label) { item { value } }
            }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), error.queryPath)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `duplicate label errors point to the conflicting use across experimental boundaries`(experimentalFirst: Boolean) {
        val experimental = "child @useExperimental { ... @defer(label: \"same\") { value } }"
        val stable = "... @defer(label: \"same\") { value }"
        val selections = if (experimentalFirst) "$experimental $stable" else "$stable $experimental"
        val error = validationError("{ item { $selections } }")

        assertEquals(if (experimentalFirst) listOf("item") else listOf("item", "child"), error.queryPath)
    }

    @ParameterizedTest
    @ValueSource(strings = ["mutation", "subscription"])
    fun `root errors on inline fragments and fragment spreads have empty query paths`(operation: String) {
        val rootType = operation.replaceFirstChar { it.uppercaseChar() }
        for (query in listOf(
            "$operation { ... @defer { item { value } } }",
            "$operation { ...Root @defer } fragment Root on $rootType { item { value } }",
            "$operation { ... { ...Root @defer } } fragment Root on $rootType { item { value } }",
        )) {
            assertEquals(emptyList<String>(), validationError(query).queryPath)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["mutation", "subscription"])
    fun `root errors inside nested named fragments use the innermost definition name`(operation: String) {
        val rootType = operation.replaceFirstChar { it.uppercaseChar() }
        val error = validationError(
            """
            $operation { ...Outer }
            fragment Outer on $rootType { ...Inner }
            fragment Inner on $rootType { ... { ... @defer { item { value } } } }
            """.trimIndent(),
        )

        assertEquals(listOf("Inner"), error.queryPath)
    }

    @ParameterizedTest
    @ValueSource(strings = ["mutation", "subscription"])
    fun `defer on a root fragment spread uses the containing fragment name`(operation: String) {
        val rootType = operation.replaceFirstChar { it.uppercaseChar() }
        val error = validationError(
            """
            $operation { ...Outer }
            fragment Outer on $rootType { ...Inner @defer }
            fragment Inner on $rootType { item { value } }
            """.trimIndent(),
        )

        assertEquals(listOf("Outer"), error.queryPath)
    }

    @ParameterizedTest
    @ValueSource(strings = ["mutation", "subscription"])
    fun `repeated root fragment spreads keep the path of each invalid directive`(operation: String) {
        val rootType = operation.replaceFirstChar { it.uppercaseChar() }
        val errors = validationErrors(
            """
            $operation { ...Outer }
            fragment Outer on $rootType { ...Inner ...Inner @defer(if: false) }
            fragment Inner on $rootType { ... @defer { item { value } } }
            """.trimIndent(),
        )

        assertEquals(listOf(listOf("Inner"), listOf("Outer")), errors.map { it.queryPath })
    }

    @Test
    fun `nested namespace errors report the namespace type and use field names instead of aliases`() {
        val error = validationError("mutation { admin { renamed: billing { ... @defer { item { value } } } } }")

        assertEquals(ValidationErrorType.MisplacedDirective, error.validationErrorType)
        assertEquals(listOf("admin", "billing"), error.queryPath)
        assertEquals(listOf(SourceLocation(1, 43)), error.locations)
        assertEquals("The @defer directive cannot be used on a selection of mutation namespace type 'Billing'.", error.description)
    }

    @Test
    fun `namespace errors inside a fragment include the fragment name and nested field path`() {
        val error = validationError(
            """
            mutation { admin { ...AdminFields ...AdminFields } }
            fragment AdminFields on Admin { billing { ... @defer { item { value } } } }
            """.trimIndent(),
        )

        assertEquals(listOf("AdminFields", "billing"), error.queryPath)
    }

    @Test
    fun `defer is rejected in mutation namespaces even when the namespace field returns a list`() {
        assertEquals(listOf("admins"), validationError("mutation { admins { ...Fields @defer } } fragment Fields on Admin { item { value } }").queryPath)
    }

    @Test
    fun `mutation namespaces reject defer even when it is disabled or its fragment is skipped`() {
        for (query in listOf(
            "mutation { admin { ... @defer(if: false) { item { value } } } }",
            "mutation(${'$'}enabled: Boolean! = false) { admin { ... @defer(if: ${'$'}enabled) { item { value } } } }",
            "mutation { admin { item { value } ... @defer @skip(if: true) { item { value } } } }",
        )) {
            assertEquals(listOf("admin"), validationError(query).queryPath)
        }
    }

    @Test
    fun `a namespace fragment shared by two mutations reports an error for each operation`() {
        val errors = validationErrors(
            """
            mutation First { admin { ...AdminFields } }
            mutation Second { admin { ...AdminFields } }
            fragment AdminFields on Admin { ... @defer { item { value } } }
            """.trimIndent(),
        )

        assertEquals(listOf(listOf("AdminFields"), listOf("AdminFields")), errors.map { it.queryPath })
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "mutation { admin { billing { item { ... @defer { value } } } } }",
            "mutation { admin { item { ...Details @defer } } } fragment Details on IncrementalItem { value }",
            "{ shared { ... @defer { item { value } } } }",
            // Leave unknown-field errors to graphql-java's normal validation.
            "mutation { missing { ... @defer { value } } }",
        ],
    )
    fun `namespace validation skips mutation results, queries and unknown fields`(query: String) {
        assertEquals(emptyList<ValidationError>(), validationErrors(query))
    }

    private fun validationError(query: String): ValidationError {
        val errors = validationErrors(query)
        assertEquals(1, errors.size, errors.toString())
        return errors.single()
    }

    private fun validationErrors(query: String): List<ValidationError> {
        val input = ExecutionInput.newExecutionInput().query(query).build()
        val entry = requireNotNull(
            provider.getDocumentAsync(input) { executionInput ->
                PreparsedDocumentEntry(Parser.parse(executionInput.query))
            }.join()
        )

        return entry.errors.map { assertInstanceOf(ValidationError::class.java, it) }
    }
}
