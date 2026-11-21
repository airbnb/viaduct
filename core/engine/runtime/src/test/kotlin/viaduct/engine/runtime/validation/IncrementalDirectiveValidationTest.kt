package viaduct.engine.runtime.validation

import graphql.ExecutionInput
import graphql.GraphQLContext
import graphql.execution.ExecutionId
import graphql.execution.MergedField
import graphql.execution.ResultPath
import graphql.execution.conditional.ConditionalNodeDecision
import graphql.execution.preparsed.NoOpPreparsedDocumentProvider
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
import graphql.language.SourceLocation
import graphql.parser.Parser
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingEnvironmentImpl
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.validation.ValidationError
import graphql.validation.ValidationErrorType
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.IntStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
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

    @ParameterizedTest
    @ValueSource(strings = ["inline", "fragment"])
    fun `subscription errors use field names through nested fields and aliases`(kind: String) {
        val selection = if (kind == "inline") "... @defer { value }" else "...Details @defer"
        val fragment = if (kind == "fragment") "fragment Details on IncrementalItem { value }" else ""
        val error = validationError(
            "subscription { renamedItem: item { renamedChild: child { $selection } } } $fragment",
        )

        assertEquals(listOf("item", "child"), error.queryPath)
    }

    @Test
    fun `subscription errors in nested named fragments use the innermost definition name`() {
        val error = validationError(
            """
            subscription { item { ...Outer } }
            fragment Outer on IncrementalItem { ...Inner }
            fragment Inner on IncrementalItem { renamedChild: child { ... @defer { value } } }
            """.trimIndent(),
        )

        assertEquals(listOf("Inner", "child"), error.queryPath)
    }

    @Test
    fun `subscription errors are appended to existing validation errors`() {
        val query = "subscription { item { ... @defer { value } } }"
        val existing = ValidationError.newValidationError()
            .validationErrorType(ValidationErrorType.FieldUndefined)
            .description("An existing validation error")
            .build()
        val input = ExecutionInput.newExecutionInput().query(query).build()

        val entry = requireNotNull(
            provider.getDocumentAsync(input) { PreparsedDocumentEntry(Parser.parse(query), listOf(existing)) }.join(),
        )

        assertEquals(2, entry.errors.size, entry.errors.toString())
        assertEquals(existing, entry.errors.first())
        assertEquals(listOf("item"), assertInstanceOf(ValidationError::class.java, entry.errors.last()).queryPath)
    }

    @Test
    fun `label and root errors do not hide errors on nested subscription defer`() {
        val errors = validationErrors(
            """
            subscription {
                ... @defer(label: 1) { item { ... @defer { value } } }
            }
            """.trimIndent(),
        )

        assertEquals(listOf(emptyList(), emptyList(), listOf("item")), errors.map { it.queryPath })
        assertEquals(1, errors.count { it.description.contains("root subscription") })
        assertEquals(1, errors.count { it.description.contains("not supported on subscription") })
    }

    @Test
    fun `a fragment visited at the subscription root is also checked when used inside a field`() {
        val errors = validationErrors(
            """
            subscription { ...Root item { ...Root } }
            fragment Root on Subscription {
                ... @defer { item { value } }
            }
            """.trimIndent(),
        )

        assertEquals(2, errors.size, errors.toString())
        assertEquals(listOf(listOf("Root"), listOf("Root")), errors.map { it.queryPath })
        assertEquals(1, errors.count { it.description.contains("not supported on subscription") })
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `concurrent completion reuses enabled and disabled defer decisions`(enabled: Boolean) {
        val decisions = AtomicInteger()
        val graphQLContext = GraphQLContext.newContext()
            .of(
                ConditionalNodeDecision::class.java,
                ConditionalNodeDecision {
                    decisions.incrementAndGet()
                    true
                },
            )
            .build()
        val environment = deferEnvironment(
            "subscription(${'$'}enabled: Boolean!) { items { ... @defer(if: ${'$'}enabled) { value } } }",
            graphQLContext,
            mapOf("enabled" to enabled),
        )

        IntStream.range(0, 16).parallel().forEach { index ->
            val path = ResultPath.rootPath().segment("items").segment(index)
            val error = SubscriptionDeferChecks.error(environment, "IncrementalItem", path)
            if (enabled) {
                assertEquals(listOf("items", index), requireNotNull(error).path)
            } else {
                assertNull(error)
            }
        }

        assertEquals(1, decisions.get(), "Completing more objects must not reevaluate the selections")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `cached checks retain the first applicable directive for each runtime type`(typedFirst: Boolean) {
        val typed = "... on IncrementalItem @defer(if: ${'$'}enabled) { value }"
        val universal = "... @defer(if: ${'$'}enabled) { value }"
        val selections = if (typedFirst) "$typed $universal" else "$universal $typed"
        val query = "subscription(${'$'}enabled: Boolean!) { items { $selections } }"
        val environment = deferEnvironment(query)

        for (typeName in listOf("IncrementalItem", "OtherIncrementalItem")) {
            val error = requireNotNull(SubscriptionDeferChecks.error(environment, typeName, ResultPath.rootPath().segment("items").segment(0)))
            val directiveIndex = if (typedFirst && typeName == "OtherIncrementalItem") query.lastIndexOf("@defer") else query.indexOf("@defer")
            assertEquals(listOf(SourceLocation(1, directiveIndex + 1)), error.locations)
        }
    }

    @Test
    fun `reusing a document and GraphQL context does not reuse another execution's defer decision`() {
        val disabled = deferEnvironment(
            "subscription(${'$'}enabled: Boolean!) { items { ... @defer(if: ${'$'}enabled) { value } } }",
            variables = mapOf("enabled" to false),
        )
        val enabled = DataFetchingEnvironmentImpl.newDataFetchingEnvironment(disabled)
            .executionId(ExecutionId.generate())
            .variables(mapOf("enabled" to true))
            .build()
        val path = ResultPath.rootPath().segment("items").segment(0)

        repeat(2) {
            assertNull(SubscriptionDeferChecks.error(disabled, "IncrementalItem", path))
            val error = requireNotNull(SubscriptionDeferChecks.error(enabled, "IncrementalItem", path))
            assertEquals(listOf("items", 0), error.path)
        }
    }

    @Test
    fun `query completion does not evaluate subscription defer checks`() {
        val decisions = AtomicInteger()
        val graphQLContext = GraphQLContext.newContext()
            .of(
                ConditionalNodeDecision::class.java,
                ConditionalNodeDecision {
                    decisions.incrementAndGet()
                    true
                },
            )
            .build()
        val environment = deferEnvironment(
            "query(${'$'}enabled: Boolean!) { items { ... @defer(if: ${'$'}enabled) { value } } }",
            graphQLContext,
        )

        assertNull(SubscriptionDeferChecks.error(environment, "IncrementalItem", ResultPath.rootPath().segment("items")))
        assertEquals(0, decisions.get(), "Queries must not traverse selections for the subscription check")
    }

    private fun deferEnvironment(
        query: String,
        graphQLContext: GraphQLContext = GraphQLContext.newContext().build(),
        variables: Map<String, Any?> = mapOf("enabled" to true),
    ): DataFetchingEnvironment {
        val document = Parser.parse(query)
        val schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(
                """
                type Query { items: [IncrementalValue] }
                type Subscription { items: [IncrementalValue] }
                interface IncrementalValue { value: Int }
                type IncrementalItem implements IncrementalValue { value: Int }
                type OtherIncrementalItem implements IncrementalValue { value: Int }
                """.trimIndent(),
            ),
        )
        val operation = document.getDefinitionsOfType(OperationDefinition::class.java).single()
        val field = operation.selectionSet.selections.single() as Field
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .executionId(ExecutionId.generate())
            .mergedField(MergedField.newMergedField(field).build())
            .document(document)
            .operationDefinition(operation)
            .fragmentsByName(document.getDefinitionsOfType(FragmentDefinition::class.java).associateBy { it.name })
            .graphQLSchema(schema)
            .graphQLContext(graphQLContext)
            .variables(variables)
            .build()
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
