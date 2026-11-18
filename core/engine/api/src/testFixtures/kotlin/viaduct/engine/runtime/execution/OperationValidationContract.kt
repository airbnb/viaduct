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

    @ParameterizedTest
    @ValueSource(strings = ["mutation", "subscription"])
    fun `mutation and subscription roots reject defer on inline fragments and fragment spreads`(operation: String) {
        val rootType = operation.replaceFirstChar { it.uppercaseChar() }
        for (query in listOf(
            "$operation { ... @defer { item { value } } }",
            "$operation { ...Root @defer } fragment Root on $rootType { item { value } }",
            "$operation { ...Root } fragment Root on $rootType { ... @defer { item { value } } }",
            "$operation { ...Outer } fragment Outer on $rootType { ...Root @defer } fragment Root on $rootType { item { value } }",
        )) {
            assertRootValidationErrors(query, operation)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["mutation", "subscription"])
    fun `disabling defer or excluding its selection does not allow defer at an operation root`(operation: String) {
        val rootType = operation.replaceFirstChar { it.uppercaseChar() }
        for (query in listOf(
            "$operation { ... @defer(if: false) { item { value } } }",
            "$operation(${'$'}enabled: Boolean! = false) { ... @defer(if: ${'$'}enabled) { item { value } } }",
            "$operation { item { value } ... @defer @skip(if: true) { item { value } } }",
            "$operation { item { value } ... @include(if: false) { ...Root @defer } } fragment Root on $rootType { item { value } }",
        )) {
            assertRootValidationErrors(query, operation)
        }
    }

    @ParameterizedTest
    @CsvSource("mutation, false", "mutation, true", "subscription, false", "subscription, true")
    fun `defer on either occurrence of a repeated root fragment spread is rejected`(
        operation: String,
        deferredFirst: Boolean,
    ) {
        val rootType = operation.replaceFirstChar { it.uppercaseChar() }
        val deferred = "...Root @defer(if: false)"
        val direct = if (deferredFirst) "$deferred ...Root" else "...Root $deferred"
        val indirect = if (deferredFirst) "$deferred ...Outer" else "...Outer $deferred"
        for (query in listOf(
            "$operation { $direct } fragment Root on $rootType { item { value } }",
            "$operation { $indirect } fragment Outer on $rootType { ...Root } fragment Root on $rootType { item { value } }",
        )) {
            assertRootValidationErrors(query, operation)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["mutation", "subscription"])
    fun `a shared root fragment is validated separately for each operation`(operation: String) {
        val rootType = operation.replaceFirstChar { it.uppercaseChar() }
        assertRootValidationErrors(
            "$operation First { ...Root } $operation Second { ...Root } fragment Root on $rootType { ... @defer { item { value } } }",
            operation,
            expectedCount = 2,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["mutation", "subscription"])
    fun `using a fragment in a query does not hide its invalid defer in another operation`(operation: String) {
        assertRootValidationErrors(
            "query Read { ...Root } $operation Write { ...Root } fragment Root on IncrementalRoot { ... @defer { item { value } } }",
            operation,
        )
    }

    @Test
    fun `query roots allow defer on inline fragments and fragment spreads`() {
        rootValidationModule("mutation").runContractTest {
            for (query in listOf(
                "{ ... @defer { item { value } } }",
                "{ ...Root @defer } fragment Root on Query { item { value } }",
            )) {
                runQuery(query).assertJson("""{"data":{"item":{"value":1}}}""")
            }
        }
    }

    @Test
    fun `mutation fields allow defer in nested selections including through root fragments`() {
        rootValidationModule("mutation").runContractTest {
            for (query in listOf(
                "mutation { item { ... @defer { value } } }",
                "mutation { item { ...Details @defer } } fragment Details on IncrementalItem { value }",
                "mutation { ...Root } fragment Root on Mutation { item { ...Details @defer } } fragment Details on IncrementalItem { value }",
            )) {
                runQuery(query).assertJson("""{"data":{"item":{"value":1}}}""")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["mutation", "subscription"])
    fun `root defer restrictions also apply when the schema uses custom root type names`(operation: String) {
        for (query in listOf(
            "$operation { ... on CustomRoot @defer { item { value } } }",
            "$operation { ...Root @defer } fragment Root on CustomRoot { item { value } }",
        )) {
            assertRootValidationErrors(query, operation, rootType = "CustomRoot")
        }
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "mutation { admin { ... @defer { item { value } } } } | AdminMutations",
            "mutation { admin { ... @defer(if: false) { item { value } } } } | AdminMutations",
            "mutation { admin { item { value } ... @defer @skip(if: true) { item { value } } } } | AdminMutations",
            "mutation { admin { ...Admin @defer } } fragment Admin on AdminMutations { item { value } } | AdminMutations",
            "mutation { admin { ...Admin } } fragment Admin on AdminMutations { ... @defer { item { value } } } | AdminMutations",
            "mutation { ...Root } fragment Root on Mutation { admin { ... @defer { item { value } } } } | AdminMutations",
            "mutation { admin { ... @defer { billing { item { value } } } } } | AdminMutations",
            "mutation { admin { billing { ... @defer { item { value } } } } } | BillingMutations",
            "mutation { admin { billing { ...Billing @defer } } } fragment Billing on BillingMutations { item { value } } | BillingMutations",
        ],
    )
    fun `defer on fragments inside mutation namespaces is rejected before resolvers run`(
        query: String,
        namespaceType: String,
    ) {
        val calls = AtomicInteger()
        val result = executeQuery(namespaceValidationModule { calls.incrementAndGet() }, query)

        assertEquals(1, result.errors.size, result.errors.toString())
        val error = result.errors.single()
        assertEquals(ErrorType.ValidationError.toString(), error.errorType.toString())
        assertTrue(error.message.contains("@defer"), error.toString())
        assertTrue(error.message.contains("mutation namespace type '$namespaceType'"), error.toString())
        assertEquals(listOf(SourceLocation(1, query.indexOf("@defer") + 1)), error.locations)
        assertEquals(0, calls.get(), "Invalid defer in a mutation namespace must be rejected before resolvers run")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "mutation { admin { item { ... @defer { value } } } } | {admin: {item: {value: 1}}}",
            "mutation { admin { billing { item { ...Details @defer } } } } fragment Details on IncrementalItem { value } | {admin: {billing: {item: {value: 1}}}}",
            "mutation { ...Root } fragment Root on Mutation { admin { item { ... @defer { value } } } } | {admin: {item: {value: 1}}}",
            "{ shared { ... @defer { item { value } } } } | {shared: {item: {value: 1}}}",
            "{ shared { ...Shared @defer } } fragment Shared on SharedNamespace { item { value } } | {shared: {item: {value: 1}}}",
        ],
    )
    fun `defer is allowed in mutation results and in queries that use namespace types`(
        query: String,
        expectedData: String,
    ) {
        namespaceValidationModule().runContractTest {
            runQuery(query).assertJson("{data: $expectedData}")
        }
    }

    private fun namespaceValidationModule(onResolve: () -> Unit = {}) =
        EngineTestModule(
            """
            extend type Query { shared: SharedNamespace }
            extend type Mutation { admin: AdminMutations, shared: SharedNamespace }
            type AdminMutations @namespaceType { item: IncrementalItem, billing: BillingMutations }
            type BillingMutations @namespaceType { item: IncrementalItem }
            type SharedNamespace @namespaceType { item: IncrementalItem }
            type IncrementalItem { value: Int }
            """.trimIndent(),
        ) {
            for (type in listOf("AdminMutations", "BillingMutations", "SharedNamespace")) {
                field(type to "item") {
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

    private fun assertRootValidationErrors(
        query: String,
        operation: String,
        rootType: String = operation.replaceFirstChar { it.uppercaseChar() },
        expectedCount: Int = 1,
    ) {
        val calls = AtomicInteger()
        val result = executeQuery(rootValidationModule(operation, rootType) { calls.incrementAndGet() }, query)
        assertEquals(expectedCount, result.errors.size, result.errors.toString())
        for (error in result.errors) {
            assertEquals(ErrorType.ValidationError.toString(), error.errorType.toString())
            assertTrue(error.message.contains("@defer"), error.toString())
            assertTrue(error.message.contains("root $operation"), error.toString())
            assertEquals(listOf(SourceLocation(1, query.indexOf("@defer") + 1)), error.locations)
        }
        assertEquals(0, calls.get(), "Invalid root directives must not execute resolvers")
    }

    private fun rootValidationModule(
        operation: String,
        rootType: String = operation.replaceFirstChar { it.uppercaseChar() },
        onResolve: () -> Unit = {},
    ) = EngineTestModule(
        """
        schema { query: Query $operation: $rootType }
        interface IncrementalRoot { item: IncrementalItem }
        type Query implements IncrementalRoot { item: IncrementalItem }
        type $rootType implements IncrementalRoot { item: IncrementalItem }
        type IncrementalItem { value: Int }
        """.trimIndent(),
    ) {
        for (type in listOf("Query", rootType)) {
            field(type to "item") {
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
