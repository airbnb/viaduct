package viaduct.engine.runtime.execution

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.FromFieldVariablesResolver
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockCheckerExecutor
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.MockVariablesResolver
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.getAs
import viaduct.engine.runtime.QueryPlanExecutionCondition
import viaduct.graphql.test.assertJson
import viaduct.service.api.spi.ErrorBuilder
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.ResolverErrorBuilder

@Suppress("UNCHECKED_CAST")
@ExperimentalCoroutinesApi
interface AccessCheckExecutionContract : QueryExecutionContract {
    val schemaSDL get() = "extend type Query { boo: Boo } type Boo { value: Int }"

    @Test
    fun `checker object selections resolve interface fields on the concrete type`() = assertCheckerInterfaceSelections(queryRoot = false)

    @Test
    fun `checker Query selections resolve interface fields on Query`() = assertCheckerInterfaceSelections(queryRoot = true)

    @Test
    fun `checker object selections discard variables from pruned branches`() = assertCheckerInterfaceSelections(queryRoot = false, variables = CheckerInterfaceVariables.PRUNED)

    @Test
    fun `checker Query selections discard variables from pruned branches`() = assertCheckerInterfaceSelections(queryRoot = true, variables = CheckerInterfaceVariables.PRUNED)

    @Test
    fun `checker object selections retain only surviving provider variables`() = assertCheckerInterfaceSelections(queryRoot = false, variables = CheckerInterfaceVariables.PROVIDER)

    @Test
    fun `checker Query selections retain only surviving provider variables`() = assertCheckerInterfaceSelections(queryRoot = true, variables = CheckerInterfaceVariables.PROVIDER)

    @Test
    fun `checker object selections omit independent providers from pruned branches`() = assertCheckerInterfaceSelections(queryRoot = false, variables = CheckerInterfaceVariables.INDEPENDENT)

    @Test
    fun `checker Query selections omit independent providers from pruned branches`() = assertCheckerInterfaceSelections(queryRoot = true, variables = CheckerInterfaceVariables.INDEPENDENT)

    @Test
    fun `checker object selections retain field variable dependencies after pruning`() = assertCheckerInterfaceSelections(queryRoot = false, variables = CheckerInterfaceVariables.FIELD)

    @Test
    fun `checker Query selections retain field variable dependencies after pruning`() = assertCheckerInterfaceSelections(queryRoot = true, variables = CheckerInterfaceVariables.FIELD)

    @ParameterizedTest(name = "{0}: excluded {1} with {2}, shared callback = {3}")
    @CsvSource(
        "Item, FIELD, @skip(if: true), false",
        "Item, FIELD, @include(if: false), false",
        "Item, INLINE_FRAGMENT, @skip(if: true), false",
        "Item, INLINE_FRAGMENT, @include(if: false), false",
        "Item, NAMED_SPREAD, @skip(if: true), false",
        "Item, NAMED_SPREAD, @include(if: false), false",
        "Query, FIELD, @skip(if: true), false",
        "Query, FIELD, @include(if: false), false",
        "Query, INLINE_FRAGMENT, @skip(if: true), false",
        "Query, INLINE_FRAGMENT, @include(if: false), false",
        "Query, NAMED_SPREAD, @skip(if: true), false",
        "Query, NAMED_SPREAD, @include(if: false), false",
        "Item, FIELD, @skip(if: true), true",
        "Query, FIELD, @skip(if: true), true",
    )
    fun `checker skips callbacks used only by statically excluded selections`(
        rootType: String,
        exclusionLocation: String,
        directive: String,
        sharedCallback: Boolean,
    ) {
        val schema = createSchemaWithWiring(
            """
                interface Named { policy(prefix: String): String }
                extend type Query implements Named { item: Item policy(prefix: String): String }
                type Item implements Named { policy(prefix: String): String checked: Int }
            """.trimIndent()
        )
        val ignored = "ignored: policy(prefix: \$unused)"
        val excludedSelection = when (exclusionLocation) {
            "FIELD" -> "$ignored $directive"
            "INLINE_FRAGMENT" -> "... on Named $directive { $ignored }"
            "NAMED_SPREAD" -> "...Ignored $directive"
            else -> error("Unknown exclusion location: $exclusionLocation")
        }
        val selections = """
            fragment Main on $rootType {
                ... on Named {
                    selected: policy(prefix: ${'$'}prefix)
                    $excludedSelection
                }
            }
            ${if (exclusionLocation == "NAMED_SPREAD") "fragment Ignored on Named { $ignored }" else ""}
        """.trimIndent()
        val providerValues = if (sharedCallback) mapOf("prefix" to "selected", "unused" to "ignored") else mapOf("prefix" to "selected")
        val activeProvider = MockVariablesResolver(*providerValues.keys.toTypedArray()) { _, _ -> providerValues }
        val required = createRSS(
            rootType,
            selections,
            variableProviders = buildList {
                add(activeProvider)
                if (!sharedCallback) add(MockVariablesResolver("unused") { _, _ -> error("Statically excluded callback must not run") })
            },
            forChecker = true,
        )
        val checkerInputs = ConcurrentLinkedQueue<EngineObjectData.Sync>()
        val checker = MockCheckerExecutor(mapOf("input" to required)) { _, inputs -> checkerInputs += inputs.getValue("input") }
        val resolvers = EngineTestModule(schema) {
            fieldWithValue("Query" to "item", createEngineObjectData(schema.schema.getObjectType("Item"), emptyMap()))
            fieldWithValue("Item" to "checked", 7)
            field(rootType to "policy") {
                resolver {
                    fn { arguments, _, _, _, _ ->
                        assertEquals("selected", arguments["prefix"])
                        "selected-policy"
                    }
                }
            }
        }
        val module = EngineTestModule(
            fullSchema = schema,
            fieldResolverExecutors = resolvers.fieldResolverExecutors,
            checkerExecutors = mapOf(("Item" to "checked") to checker),
            typeCheckerExecutors = mapOf("Item" to checker),
        )

        module.runContractTest {
            val result = withTimeout("{ item { checked } }", timeoutMillis = 5_000)

            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(mapOf("item" to mapOf("checked" to 7)), result.getData())
            assertEquals(2, checkerInputs.size)
            checkerInputs.forEach { input -> assertEquals("selected-policy", input.get("selected")) }
        }
    }

    @ParameterizedTest(name = "{0}: shared callback = {1}, directive variable = {2}")
    @CsvSource(
        "Item, false, false",
        "Item, true, false",
        "Query, false, false",
        "Query, true, false",
        "Item, false, true",
        "Item, true, true",
        "Query, false, true",
        "Query, true, true",
    )
    fun `checker skips disabled RSS callbacks while retaining callbacks needed by enabled inputs`(
        rootType: String,
        sharedCallback: Boolean,
        directiveVariable: Boolean,
    ) {
        val schema = createSchemaWithWiring(
            """
                extend type Query { item: Item policy(prefix: String): String }
                type Item { checked: Int policy(prefix: String): String }
            """.trimIndent()
        )
        val callbackCalls = ConcurrentLinkedQueue<String>()
        val activeValues = if (sharedCallback) {
            mapOf("prefix" to "selected", "unused" to if (directiveVariable) true else "ignored")
        } else {
            mapOf("prefix" to "selected")
        }
        val activeCallback = MockVariablesResolver(*activeValues.keys.toTypedArray()) { _, _ ->
            callbackCalls += "active"
            activeValues
        }
        val disabledCallback = if (sharedCallback) {
            activeCallback
        } else {
            MockVariablesResolver("unused") { _, _ -> error("Disabled RSS callback must not run") }
        }
        val required = mapOf(
            "active" to createRSS(
                rootType,
                "selected: policy(prefix: \$prefix)",
                variableProviders = listOf(activeCallback),
                forChecker = true,
            ),
            "disabled" to createRSS(
                rootType,
                if (directiveVariable) "ignored: policy(prefix: \"ignored\") @include(if: \$unused)" else "ignored: policy(prefix: \$unused)",
                variableProviders = listOf(disabledCallback),
                forChecker = true,
                executionCondition = QueryPlanExecutionCondition { false },
            ),
        )
        val resolvers = EngineTestModule(schema) {
            fieldWithValue("Query" to "item", createEngineObjectData(schema.schema.getObjectType("Item"), emptyMap()))
            fieldWithValue("Item" to "checked", 7)
            field(rootType to "policy") {
                resolver {
                    fn { arguments, _, _, _, _ ->
                        assertEquals("selected", arguments["prefix"])
                        "selected-policy"
                    }
                }
            }
        }

        fun execute(includeDisabled: Boolean): Int {
            callbackCalls.clear()
            val checkerInputs = ConcurrentLinkedQueue<Map<String, EngineObjectData.Sync>>()
            val inputsToLoad = if (includeDisabled) required else required.filterKeys { it == "active" }
            val checker = MockCheckerExecutor(inputsToLoad) { _, inputs -> checkerInputs += inputs }
            val module = EngineTestModule(
                fullSchema = schema,
                fieldResolverExecutors = resolvers.fieldResolverExecutors,
                checkerExecutors = mapOf(("Item" to "checked") to checker),
            )

            module.runContractTest {
                val result = withTimeout("{ item { checked } }", timeoutMillis = 5_000)

                assertTrue(result.errors.isEmpty(), result.errors.toString())
                assertEquals(mapOf("item" to mapOf("checked" to 7)), result.getData())
                val inputs = checkerInputs.single()
                assertEquals("selected-policy", inputs.getValue("active").get("selected"))
                if (includeDisabled) assertTrue(inputs.getValue("disabled").getSelections().none())
            }
            return callbackCalls.size
        }

        val enabledOnlyCalls = execute(includeDisabled = false)
        assertTrue(enabledOnlyCalls > 0)
        assertEquals(enabledOnlyCalls, execute(includeDisabled = true), "A disabled RSS must not add callback invocations")
    }

    @ParameterizedTest
    @CsvSource("Item", "Query")
    fun `disabled checker RSS skips its field dependencies and their callbacks`(rootType: String) {
        val schema = createSchemaWithWiring(
            """
                extend type Query { item: Item policy(prefix: String): String prefixSeed(value: String): String }
                type Item { checked: Int policy(prefix: String): String prefixSeed(value: String): String }
            """.trimIndent()
        )
        val seedProvider = MockVariablesResolver("seed") { _, _ -> error("Disabled dependency callback must not run") }
        val dependency = createRSS(
            rootType,
            "prefixSeed(value: \$seed)",
            variableProviders = listOf(seedProvider),
            forChecker = true,
            executionCondition = QueryPlanExecutionCondition { error("Disabled dependency condition must not run") },
        )
        val required = createRSS(
            rootType,
            "prefixSeed(value: \$seed) policy(prefix: \$prefix)",
            variableProviders = listOf(seedProvider, FromFieldVariablesResolver("prefix", listOf("prefixSeed"), dependency)),
            forChecker = true,
            executionCondition = QueryPlanExecutionCondition { false },
        )
        val checkerInputs = ConcurrentLinkedQueue<EngineObjectData.Sync>()
        val checker = MockCheckerExecutor(mapOf("input" to required)) { _, inputs -> checkerInputs += inputs.getValue("input") }
        val resolvers = EngineTestModule(schema) {
            fieldWithValue("Query" to "item", createEngineObjectData(schema.schema.getObjectType("Item"), emptyMap()))
            fieldWithValue("Item" to "checked", 7)
            listOf("policy", "prefixSeed").forEach { fieldName ->
                field(rootType to fieldName) {
                    resolver { fn { _, _, _, _, _ -> error("Disabled dependency field must not run") } }
                }
            }
        }
        val module = EngineTestModule(
            fullSchema = schema,
            fieldResolverExecutors = resolvers.fieldResolverExecutors,
            checkerExecutors = mapOf(("Item" to "checked") to checker),
        )

        module.runContractTest {
            val result = withTimeout("{ item { checked } }", timeoutMillis = 5_000)

            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(mapOf("item" to mapOf("checked" to 7)), result.getData())
            assertTrue(checkerInputs.single().getSelections().none())
        }
    }

    private enum class CheckerInterfaceVariables { NONE, PRUNED, PROVIDER, INDEPENDENT, FIELD }

    private fun assertCheckerInterfaceSelections(
        queryRoot: Boolean,
        variables: CheckerInterfaceVariables = CheckerInterfaceVariables.NONE,
    ) {
        val schema = createSchemaWithWiring(
            """
                interface Named { owner(prefix: String): Owner }
                extend type Query implements Named { item: Item owner(prefix: String): Owner prefixSeed(suffix: String): String }
                type Item implements Named { owner(prefix: String): Owner checked: Int prefixSeed(suffix: String): String }
                type Other implements Named { owner(prefix: String): Owner other: String }
                type Owner { name: String }
            """.trimIndent()
        )
        val rootType = if (queryRoot) "Query" else "Item"
        val ownerArguments = ConcurrentLinkedQueue<Map<String, Any?>>()
        val seedArguments = ConcurrentLinkedQueue<Map<String, Any?>>()
        val checkerInputs = ConcurrentLinkedQueue<EngineObjectData.Sync>()
        val providerValues = when (variables) {
            CheckerInterfaceVariables.NONE, CheckerInterfaceVariables.INDEPENDENT -> emptyMap()
            CheckerInterfaceVariables.PRUNED -> mapOf("onlyOther" to true)
            CheckerInterfaceVariables.PROVIDER -> mapOf("onlyOther" to true, "prefix" to "selected")
            CheckerInterfaceVariables.FIELD -> mapOf("onlyOther" to true, "seed" to "selected")
        }
        val provider = providerValues.takeIf { it.isNotEmpty() }?.let { values ->
            MockVariablesResolver(*values.keys.toTypedArray()) { _, _ -> values }
        }
        val variableProviders = buildList {
            addAll(listOfNotNull(provider))
            if (variables == CheckerInterfaceVariables.INDEPENDENT) {
                add(MockVariablesResolver("onlyOther") { _, _ -> error("Pruned provider must not run") })
                add(MockVariablesResolver("prefix") { _, _ -> mapOf("prefix" to "selected") })
            }
            if (variables == CheckerInterfaceVariables.FIELD) {
                val dependency = createRSS(
                    rootType,
                    "prefixSeed(suffix: \$seed)",
                    variableProviders = listOf(requireNotNull(provider)),
                    forChecker = true,
                )
                add(FromFieldVariablesResolver("prefix", listOf("prefixSeed"), dependency))
            }
        }
        val prefix = when (variables) {
            CheckerInterfaceVariables.NONE, CheckerInterfaceVariables.PRUNED -> "\"selected\""
            else -> "\$prefix"
        }
        val otherCondition = if (variables == CheckerInterfaceVariables.NONE) "" else "@include(if: \$onlyOther)"
        val seedSelection = if (variables == CheckerInterfaceVariables.FIELD) "prefixSeed(suffix: \$seed)" else ""
        val selections = """
            $seedSelection
            ... on Named {
                selected: owner(prefix: $prefix) { name }
                ignored: owner(prefix: "ignored") @skip(if: true) { name }
                ... on Other { other $otherCondition }
            }
        """.trimIndent()
        val checker = MockCheckerExecutor(
            requiredSelectionSets = mapOf("input" to createRSS(rootType, selections, variableProviders, forChecker = true)),
        ) { _, inputs -> checkerInputs += inputs.getValue("input") }
        val resolvers = EngineTestModule(schema) {
            fieldWithValue("Query" to "item", createEngineObjectData(schema.schema.getObjectType("Item"), emptyMap()))
            fieldWithValue("Item" to "checked", 7)
            field(rootType to "owner") {
                resolver {
                    fn { arguments, _, _, _, _ ->
                        ownerArguments += arguments
                        createEngineObjectData(schema.schema.getObjectType("Owner"), mapOf("name" to "${arguments["prefix"]}-owner"))
                    }
                }
            }
            field("Other" to "other") {
                resolver { fn { _, _, _, _, _ -> error("An unrelated type's dependency must not execute") } }
            }
            field(rootType to "prefixSeed") {
                resolver {
                    fn { arguments, _, _, _, _ ->
                        seedArguments += arguments
                        arguments.getValue("suffix")
                    }
                }
            }
        }
        val module = EngineTestModule(
            fullSchema = schema,
            fieldResolverExecutors = resolvers.fieldResolverExecutors,
            checkerExecutors = mapOf(("Item" to "checked") to checker),
            typeCheckerExecutors = mapOf("Item" to checker),
        )

        module.runContractTest {
            val result = withTimeout("{ item { checked } }", timeoutMillis = 5_000)

            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(mapOf("item" to mapOf("checked" to 7)), result.getData())
            assertEquals(setOf(mapOf("prefix" to "selected")), ownerArguments.toSet())
            assertEquals(
                if (variables == CheckerInterfaceVariables.FIELD) setOf(mapOf("suffix" to "selected")) else emptySet(),
                seedArguments.toSet(),
            )
            assertEquals(2, checkerInputs.size)
            checkerInputs.forEach { input ->
                assertEquals("selected-owner", input.getAs<EngineObjectData.Sync>("selected").get("name"))
            }
        }
    }

    enum class CheckerBehavior { NONE, ALLOW_BOTH, DENY_PARENT, DENY_CHILD }

    data class ExecutionOutcome(
        val result: graphql.ExecutionResult,
        val parentCheckerCalled: Boolean,
        val childCheckerCalled: Boolean,
    )

    fun execute(behavior: CheckerBehavior): ExecutionOutcome {
        val schema = createSchemaWithWiring(schemaSDL)
        val booType = schema.schema.getObjectType("Boo")
        var parentCheckerCalled = false
        var childCheckerCalled = false
        val module = EngineTestModule(schema) {
            fieldWithValue("Query" to "boo", createEngineObjectData(booType, mapOf("value" to 5)))
            if (behavior == CheckerBehavior.ALLOW_BOTH || behavior == CheckerBehavior.DENY_PARENT) {
                field("Query" to "boo") {
                    checker {
                        fn { _, _ ->
                            parentCheckerCalled = true
                            if (behavior == CheckerBehavior.DENY_PARENT) throw RuntimeException("permission denied")
                        }
                    }
                }
            }
            if (behavior == CheckerBehavior.ALLOW_BOTH || behavior == CheckerBehavior.DENY_CHILD) {
                field("Boo" to "value") {
                    checker {
                        fn { _, _ ->
                            childCheckerCalled = true
                            if (behavior == CheckerBehavior.DENY_CHILD) throw RuntimeException("permission denied")
                        }
                    }
                }
            }
        }
        return ExecutionOutcome(executeQuery(module, "{ boo { value } }"), parentCheckerCalled, childCheckerCalled)
    }

    @Test
    fun `field executes without checkers`() {
        val outcome = execute(CheckerBehavior.NONE)
        assertEquals(mapOf("boo" to mapOf("value" to 5)), outcome.result.toSpecification()["data"])
        assertTrue(outcome.result.errors.isEmpty())
        assertFalse(outcome.parentCheckerCalled)
        assertFalse(outcome.childCheckerCalled)
    }

    @Test
    fun `parent and child checkers execute`() {
        val outcome = execute(CheckerBehavior.ALLOW_BOTH)
        assertEquals(mapOf("boo" to mapOf("value" to 5)), outcome.result.toSpecification()["data"])
        assertTrue(outcome.result.errors.isEmpty())
        assertTrue(outcome.parentCheckerCalled)
        assertTrue(outcome.childCheckerCalled)
    }

    @Test
    fun `parent checker denial nulls parent field`() {
        val outcome = execute(CheckerBehavior.DENY_PARENT)
        assertEquals(mapOf("boo" to null), outcome.result.toSpecification()["data"])
        assertEquals(1, outcome.result.errors.size)
        assertEquals(listOf("boo"), outcome.result.errors.single().path)
        assertTrue(outcome.result.errors.single().message.contains("permission denied"))
    }

    @Test
    fun `child checker denial nulls child field`() {
        val outcome = execute(CheckerBehavior.DENY_CHILD)
        assertEquals(mapOf("boo" to mapOf("value" to null)), outcome.result.toSpecification()["data"])
        assertEquals(1, outcome.result.errors.size)
        assertEquals(listOf("boo", "value"), outcome.result.errors.single().path)
        assertTrue(outcome.result.errors.single().message.contains("permission denied"))
    }

    companion object {
        val SDL = """
            extend type Query {
                string1: String
                string2: String
                boo: Boo
                baz: Baz
                bazList: [Baz]
                nonNullBaz: Baz!
                plusOne(value: Int): Int
                plusMany(value: [Int]): Int
            }

            extend type Mutation {
                string1: String
            }

            type Boo {
                value: Int
            }

            type Baz implements Node {
                id: ID!
                x: Int
                y: String
            }

            type Bar implements Node {
                id: ID!
                z: Int
            }
        """.trimIndent()

        private val schema = createSchemaWithWiring(SDL)
        private val booType = schema.schema.getObjectType("Boo")
        private val bazType = schema.schema.getObjectType("Baz")
        private val barType = schema.schema.getObjectType("Bar")
    }

    @Test
    fun `async field throws, checker throws`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "boo") {
                    resolver {
                        fn { _, _, _, _, _ -> throw RuntimeException("not found") }
                    }
                    checker {
                        fn { _, _ -> throw RuntimeException("permission denied") }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ boo { value } }")
            assertEquals(mapOf("boo" to null), result.getData())
            // resolver error takes priority
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("boo"), error.path)
            assertTrue(error.message.contains("not found"))
        }
    }

    @Test
    fun `sync field throws, checker throws`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "boo") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            object : EngineObjectData {
                                override val type = booType

                                override suspend fun fetch(selection: String): Any? {
                                    if (selection == "value") {
                                        throw RuntimeException("FakeBoo.value is throwing")
                                    }
                                    return null
                                }

                                override suspend fun fetchOrNull(selection: String) = fetch(selection)

                                override suspend fun fetchSelections() = listOf("value")
                            }
                        }
                    }
                }
                field("Boo" to "value") {
                    checker {
                        fn { _, _ -> throw RuntimeException("permission denied") }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ boo { value } }")
            assertEquals(mapOf("boo" to mapOf("value" to null)), result.getData())
            // datafetcher error takes priority
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("boo", "value"), error.path)
            assertTrue(error.message.contains("FakeBoo.value is throwing"))
        }
    }

    @Test
    fun `objectValueFragment field - field and checker successful`() {
        runQueryTest(
            EngineTestModule(schema) {
                fieldWithValue("Query" to "string2", "2nd")
                field("Query" to "string1") {
                    resolver {
                        objectSelections("string2")
                        fn { _, obj, _, _, _ ->
                            val value = obj.fetch("string2")
                            "1st & $value"
                        }
                    }
                }
                field("Query" to "string2") {
                    checker {
                        fn { _, _ -> /* access granted */ }
                    }
                }
            }
        ) { runQuery ->
            runQuery("{ string1 }")
                .assertJson("{data: {string1: \"1st & 2nd\"}}")
        }
    }

    @Test
    fun `objectValueFragment field - field successful, checker throws`() {
        runQueryTest(
            EngineTestModule(schema) {
                fieldWithValue("Query" to "string2", "2nd")
                field("Query" to "string1") {
                    resolver {
                        objectSelections("string2")
                        fn { _, obj, _, _, _ ->
                            val value = obj.fetch("string2")
                            "1st & $value"
                        }
                    }
                }
                field("Query" to "string2") {
                    checker {
                        fn { _, _ -> throw RuntimeException("string2 permission denied") }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ string1 }")
            assertEquals(mapOf("string1" to null), result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("string1"), error.path)
            assertTrue(error.message.contains("string2 permission denied"))
        }
    }

    @Test
    fun `objectValueFragment field - field throws, checker throws`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "string2") {
                    resolver {
                        fn { _, _, _, _, _ -> throw RuntimeException("string2 resolver throws") }
                    }
                    checker {
                        fn { _, _ -> throw RuntimeException("string2 permission denied") }
                    }
                }
                field("Query" to "string1") {
                    resolver {
                        objectSelections("string2")
                        fn { _, obj, _, _, _ ->
                            val value = obj.fetch("string2")
                            "1st & $value"
                        }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ string1 }")
            assertEquals(mapOf("string1" to null), result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("string1"), error.path)
            // datafetcher error takes priority
            assertTrue(error.message.contains("string2 resolver throws"))
        }
    }

    @Test
    fun `objectValueFragment field - field throws, checker successful`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "string2") {
                    resolver {
                        fn { _, _, _, _, _ -> throw RuntimeException("string2 resolver throws") }
                    }
                    checker {
                        fn { _, _ -> /* access granted */ }
                    }
                }
                field("Query" to "string1") {
                    resolver {
                        objectSelections("string2")
                        fn { _, obj, _, _, _ ->
                            val value = obj.fetch("string2")
                            "1st & $value"
                        }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ string1 }")
            assertEquals(mapOf("string1" to null), result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("string1"), error.path)
            assertTrue(error.message.contains("string2 resolver throws"))
        }
    }

    @Test
    fun `checker with rss - access checks skipped`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "string1") {
                    value("foo")
                    checker {
                        objectSelections("key", "fragment _ on Query { plusOne(value: \$var) }") {
                            variables("var", rss = createRSS("Query", "string2", forChecker = true)) { ctx, _ ->
                                val string2 = ctx.objectData.get("string2") as String
                                mapOf("var" to string2.toInt())
                            }
                        }
                        fn { _, objectDataMap ->
                            if (objectDataMap["key"]?.fetch("plusOne") == 5) {
                                // Getting here verifies the checkers were not executed
                                throw RuntimeException("this should get thrown")
                            }
                        }
                    }
                }
                field("Query" to "string2") {
                    value("4")
                    checker {
                        // [Regression test] This shouldn't execute since it's in a checker variable resolver RSS
                        fn { _, _ -> throw RuntimeException("Query.string2 checker failed") }
                    }
                }
                field("Query" to "plusOne") {
                    resolver {
                        fn { arguments, _, _, _, _ ->
                            (arguments["value"] as Int) + 1
                        }
                    }
                    checker {
                        // This shouldn't execute since it's in a checker RSS
                        fn { _, _ -> throw RuntimeException("Query.plusOne checker failed") }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ string1 }")
            assertEquals(mapOf("string1" to null), result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("string1"), error.path)
            assertTrue(error.message.contains("this should get thrown"))
        }
    }

    @Test
    fun `mutation field with checker`() {
        var mutationResolverRan = false

        runQueryTest(
            EngineTestModule(schema) {
                field("Mutation" to "string1") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            mutationResolverRan = true
                            "foo"
                        }
                    }
                    checker {
                        fn { _, _ -> throw RuntimeException("string1 checker failed") }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("mutation { string1 }")
            assertEquals(mapOf("string1" to null), result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("string1"), error.path)
            assertTrue(error.message.contains("string1 checker failed"))
        }

        assertFalse(mutationResolverRan)
    }

    @Test
    fun `field and type checks fail`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "baz") {
                    resolver {
                        fn { _, _, _, _, ctx -> ctx.createNodeReference("1", bazType) }
                    }
                    checker {
                        fn { _, _ -> throw RuntimeException("field checker failed") }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        createEngineObjectData(bazType, mapOf("id" to id, "x" to id.toInt(), "y" to id))
                    }
                    checker {
                        fn { _, _ -> throw RuntimeException("type checker failed") }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ baz { id } }")
            assertEquals(mapOf("baz" to null), result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("baz"), error.path)
            assertTrue(error.message.contains("field checker failed"))
        }
    }

    @Test
    fun `field check succeeds type check fails`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "boo") {
                    resolver {
                        fn { _, _, _, _, _ -> createEngineObjectData(booType, mapOf("value" to 2)) }
                    }
                    checker {
                        fn { _, _ -> /* access granted */ }
                    }
                }
                type("Boo") {
                    checker {
                        fn { _, _ -> throw RuntimeException("type checker failed") }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ boo { value } }")
            assertEquals(mapOf("boo" to null), result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("boo"), error.path)
            assertTrue(error.message.contains("type checker failed"))
        }
    }

    @Test
    fun `field check succeeds, type check succeeds`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "baz") {
                    resolver {
                        fn { _, _, _, _, ctx -> ctx.createNodeReference("1", bazType) }
                    }
                    checker {
                        fn { _, _ -> /* access granted */ }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        createEngineObjectData(bazType, mapOf("id" to id, "x" to id.toInt(), "y" to id))
                    }
                    checker {
                        fn { _, _ -> /* access granted */ }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ baz { id } }")
            assertEquals(mapOf("baz" to mapOf("id" to "1")), result.getData())
            assertEquals(0, result.errors.size)
        }
    }

    @Test
    fun `no field check, type check fails`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "baz") {
                    resolver {
                        fn { _, _, _, _, ctx -> ctx.createNodeReference("1", bazType) }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        createEngineObjectData(bazType, mapOf("id" to id, "x" to id.toInt(), "y" to id))
                    }
                    checker {
                        fn { _, _ -> throw RuntimeException("type checker failed") }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ baz { id } }")
            assertEquals(mapOf("baz" to null), result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("baz"), error.path)
            assertTrue(error.message.contains("type checker failed"))
        }
    }

    @Test
    fun `no field check, type check fails on non-null field`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "nonNullBaz") {
                    resolver {
                        fn { _, _, _, _, ctx -> ctx.createNodeReference("1", bazType) }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        createEngineObjectData(bazType, mapOf("id" to id, "x" to id.toInt(), "y" to id))
                    }
                    checker {
                        fn { _, _ -> throw RuntimeException("type checker failed") }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ nonNullBaz { id } }")
            assertNull(result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("nonNullBaz"), error.path)
            assertTrue(error.message.contains("type checker failed"))
        }
    }

    @Test
    fun `type check fail on interface field`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "node") {
                    resolver {
                        fn { _, _, _, _, ctx -> ctx.createNodeReference("1", bazType) }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        createEngineObjectData(bazType, mapOf("id" to id, "x" to id.toInt(), "y" to id))
                    }
                    checker {
                        fn { _, _ -> throw RuntimeException("type checker failed") }
                    }
                }
            },
            withoutDefaultQueryNodeResolvers = true
        ) { runQuery ->
            val result = runQuery("{ node(id: \"a\") { id } }")
            assertEquals(mapOf("node" to null), result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("node"), error.path)
            assertTrue(error.message.contains("type checker failed"))
        }
    }

    @Test
    fun `type check succeed on interface field`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "node") {
                    resolver {
                        fn { _, _, _, _, ctx -> ctx.createNodeReference("1", bazType) }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        createEngineObjectData(bazType, mapOf("id" to id, "x" to id.toInt(), "y" to id))
                    }
                    checker {
                        fn { _, _ -> /* access granted */ }
                    }
                }
            },
            withoutDefaultQueryNodeResolvers = true
        ) { runQuery ->
            val result = runQuery("{ node(id: \"a\") { id ... on Baz { x y } } }")
            val expectedData = mapOf("node" to mapOf("id" to "1", "x" to 1, "y" to "1"))
            assertEquals(expectedData, result.getData())
            assertEquals(0, result.errors.size)
        }
    }

    @Test
    fun `type check with rss`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "baz") {
                    resolver {
                        fn { _, _, _, _, ctx -> ctx.createNodeReference("1", bazType) }
                    }
                }
                field("Baz" to "y") {
                    resolver {
                        objectSelections("x")
                        fn { _, obj, _, _, _ -> obj.fetch("x").toString() }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        createEngineObjectData(bazType, mapOf("id" to id, "x" to id.toInt(), "y" to id))
                    }
                    checker {
                        objectSelections("key", "fragment _ on Baz { y }")
                        fn { _, objectDataMap ->
                            if (objectDataMap["key"]!!.fetch("y") == "1") {
                                // Verifies that this got the correct value for a dependent field
                                // without a circular dep on the type checker
                                throw RuntimeException("this should get thrown")
                            }
                        }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ baz { id } }")
            assertEquals(mapOf("baz" to null), result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("baz"), error.path)
            assertTrue(error.message.contains("this should get thrown"))
        }
    }

    @Test
    fun `type check with rss - access checks skipped`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "baz") {
                    resolver {
                        fn { _, _, _, _, ctx -> ctx.createNodeReference("1", bazType) }
                    }
                }
                field("Baz" to "y") {
                    resolver {
                        objectSelections("x")
                        fn { _, obj, _, _, _ -> obj.fetch("x").toString() }
                    }
                    checker {
                        // Verifies that the access check for y shouldn't be applied
                        // during fetch, otherwise the error would be "y checker failed"
                        fn { _, _ -> throw RuntimeException("y checker failed") }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        createEngineObjectData(bazType, mapOf("id" to id, "x" to id.toInt(), "y" to id))
                    }
                    checker {
                        objectSelections("key", "fragment _ on Baz { y }")

                        fn { _, objectDataMap ->
                            if (objectDataMap["key"]!!.fetch("y") == "1") {
                                return@fn Unit
                            }
                        }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ baz { id } }")
            assertEquals(mapOf("baz" to mapOf("id" to "1")), result.getData())
            assertEquals(0, result.errors.size)
        }
    }

    @Test
    fun `type checks with rss for list of objects - fail one of them`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "bazList") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            listOf(
                                ctx.createNodeReference("1", bazType),
                                ctx.createNodeReference("2", bazType),
                                ctx.createNodeReference("3", bazType),
                            )
                        }
                    }
                }
                field("Baz" to "y") {
                    resolver {
                        objectSelections("x")
                        fn { _, obj, _, _, _ ->
                            val x = obj.fetch("x")
                            x.toString()
                        }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        createEngineObjectData(bazType, mapOf("id" to id, "x" to id.toInt(), "y" to id))
                    }
                    checker {
                        objectSelections("key", "fragment _ on Baz { y }")
                        fn { _, objectDataMap ->
                            val eod = objectDataMap["key"]!!
                            val y = eod.fetch("y")
                            if (y == "2") {
                                throw RuntimeException("permission denied for baz with internal ID 2")
                            }
                        }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ bazList { id } }")
            val expectedData = mapOf(
                "bazList" to listOf(
                    mapOf("id" to "1"),
                    null,
                    mapOf("id" to "3")
                )
            )
            assertEquals(expectedData, result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("bazList", 1), error.path)
            assertTrue(error.message.contains("permission denied for baz with internal ID 2"))
        }
    }

    @Test
    fun `type checks with rss for list of objects - all succeed`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "bazList") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            listOf(
                                ctx.createNodeReference("1", bazType),
                                ctx.createNodeReference("2", bazType),
                                ctx.createNodeReference("3", bazType),
                            )
                        }
                    }
                }
                field("Baz" to "y") {
                    resolver {
                        objectSelections("x")
                        fn { _, obj, _, _, _ ->
                            val x = obj.fetch("x")
                            x.toString()
                        }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        createEngineObjectData(bazType, mapOf("id" to id, "x" to id.toInt(), "y" to id))
                    }
                    checker {
                        objectSelections("key", "fragment _ on Baz { y }")
                        fn { _, objectDataMap ->
                            val eod = objectDataMap["key"]!!
                            val y = eod.fetch("y")
                            if (y == null) {
                                throw RuntimeException("should not get here")
                            }
                        }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ bazList { id } }")
            val expectedData = mapOf(
                "bazList" to listOf(
                    mapOf("id" to "1"),
                    mapOf("id" to "2"),
                    mapOf("id" to "3")
                )
            )
            assertEquals(expectedData, result.getData())
            assertEquals(0, result.errors.size)
        }
    }

    @Test
    fun `type checks with rss for list of polymorphic objects - fail one of them`() {
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "nodes") {
                    resolver {
                        fn { _, _, _, _, ctx ->
                            listOf(
                                ctx.createNodeReference("1", bazType),
                                ctx.createNodeReference("2", barType),
                                ctx.createNodeReference("3", barType),
                            )
                        }
                    }
                }
                field("Baz" to "y") {
                    resolver {
                        objectSelections("x")
                        fn { _, obj, _, _, _ ->
                            val x = obj.fetch("x")
                            x.toString()
                        }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        createEngineObjectData(bazType, mapOf("id" to id, "x" to id.toInt(), "y" to id))
                    }
                    checker {
                        objectSelections("key", "fragment _ on Baz { y }")
                        fn { _, objectDataMap ->
                            val eod = objectDataMap["key"]!!
                            val y = eod.fetch("y")
                            if (y == null) {
                                throw RuntimeException("should not get here")
                            }
                        }
                    }
                }
                type("Bar") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        createEngineObjectData(barType, mapOf("id" to id, "z" to id.toInt()))
                    }
                    checker {
                        objectSelections("key", "fragment _ on Bar { z }")
                        fn { _, objectDataMap ->
                            val eod = objectDataMap["key"]!!
                            val z = eod.fetch("z")
                            if (z == 2) {
                                throw RuntimeException("permission denied for bar with internal ID 2")
                            }
                        }
                    }
                }
            },
            withoutDefaultQueryNodeResolvers = true
        ) { runQuery ->
            val result = runQuery("{ nodes(ids: [\"1\", \"2\", \"3\"]) { id } }")
            val expectedData = mapOf(
                "nodes" to listOf(
                    mapOf("id" to "1"),
                    null,
                    mapOf("id" to "3")
                )
            )
            assertEquals(expectedData, result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("nodes", 1), error.path)
            assertTrue(error.message.contains("permission denied for bar with internal ID 2"))
        }
    }

    @Test
    fun `field checker with query selections`() {
        // Test that field checkers can use querySelections to access other Query fields
        runQueryTest(
            EngineTestModule(schema) {
                fieldWithValue("Query" to "string2", "test_value")
                field("Query" to "string1") {
                    value("foo")
                    checker {
                        querySelections("key", "fragment _ on Query { string2 }")
                        fn { _, objectDataMap ->
                            val string2Value = objectDataMap["key"]?.fetch("string2")
                            if (string2Value == "test_value") {
                                throw RuntimeException("field checker accessed query field")
                            }
                        }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ string1 }")
            assertEquals(mapOf("string1" to null), result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("string1"), error.path)
            assertTrue(error.message.contains("field checker accessed query field"))
        }
    }

    @Test
    fun `type checker with query selections`() {
        // Test that type checkers can use querySelections to access Query fields
        runQueryTest(
            EngineTestModule(schema) {
                fieldWithValue("Query" to "string2", "query_value")
                field("Query" to "baz") {
                    resolver {
                        fn { _, _, _, _, ctx -> ctx.createNodeReference("1", bazType) }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        createEngineObjectData(bazType, mapOf("id" to id, "x" to id.toInt(), "y" to id))
                    }
                    checker {
                        querySelections("key", "fragment _ on Query { string2 }")
                        fn { _, objectDataMap ->
                            val string2Value = objectDataMap["key"]?.fetch("string2")
                            if (string2Value == "query_value") {
                                throw RuntimeException("type checker accessed query field")
                            }
                        }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ baz { id } }")
            assertEquals(mapOf("baz" to null), result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("baz"), error.path)
            assertTrue(error.message.contains("type checker accessed query field"))
        }
    }

    @Test
    fun `checker EOD variables are coerced`() {
        // Regression test for coercing variables
        val plusManyValue = AtomicReference<Any?>()
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "string1") {
                    value("foo")
                    checker {
                        objectSelections("key", "fragment _ on Query { plusMany(value: \$var) }") {
                            variables("var") { _, _ -> mapOf("var" to 3) }
                        }
                        fn { _, objectDataMap ->
                            plusManyValue.set(objectDataMap["key"]?.fetch("plusMany"))
                        }
                    }
                }
                field("Query" to "plusMany") {
                    resolver {
                        fn { arguments, _, _, _, _ ->
                            (arguments["value"] as List<Int>).sum() + 1
                        }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ string1 }")
            assertEquals(mapOf("string1" to "foo"), result.getData())
            assertEquals(4, plusManyValue.get())
        }
    }

    @Test
    fun `type checker child plan variables are coerced`() {
        // Regression test for coercing variables in type checker child plans
        val plusManyValue = AtomicReference<Any?>()
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "baz") {
                    resolver {
                        fn { _, _, _, _, ctx -> ctx.createNodeReference("1", bazType) }
                    }
                }
                type("Baz") {
                    nodeUnbatchedExecutor { id, _, _ ->
                        createEngineObjectData(bazType, mapOf("id" to id, "x" to id.toInt(), "y" to id))
                    }
                    checker {
                        querySelections("key", "fragment _ on Query { plusMany(value: \$var) }") {
                            variables("var") { _, _ -> mapOf("var" to 6) }
                        }
                        fn { _, objectDataMap ->
                            plusManyValue.set(objectDataMap["key"]?.fetch("plusMany"))
                        }
                    }
                }
                field("Query" to "plusMany") {
                    resolver {
                        fn { arguments, _, _, _, _ ->
                            (arguments["value"] as List<Int>).sum() + 1
                        }
                    }
                }
            }
        ) { runQuery ->
            val result = runQuery("{ baz { id } }")
            assertEquals(mapOf("baz" to mapOf("id" to "1")), result.getData())
            assertEquals(7, plusManyValue.get())
        }
    }

    @Test
    fun `field access check errors are handled`() {
        var reported: Boolean = false
        val engineConfig = engineConfigWithHandler { exception ->
            if (exception is IllegalAccessException) reported = true
        }
        runQueryTest(
            EngineTestModule(schema) {
                fieldWithValue("Query" to "string1", "hello, world")
                field("Query" to "string1") {
                    checker {
                        fn { _, _ -> throw IllegalAccessException("permission denied") }
                    }
                }
            },
            engineConfig = engineConfig
        ) { runQuery ->
            val result = runQuery("{ string1 }")
            assertEquals(mapOf("string1" to null), result.getData())
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("string1"), error.path)
            assertTrue(error.message.contains("permission denied"))
            assertEquals("FOO", error.extensions.get("type"))
            assertTrue(reported)
        }
    }

    @Test
    fun `list item access check errors are handled`() {
        var reported: Boolean = false
        val engineConfig = engineConfigWithHandler { exception ->
            if (exception is IllegalAccessException) reported = true
        }
        runQueryTest(
            EngineTestModule(schema) {
                field("Query" to "bazList") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            listOf(
                                createEngineObjectData(bazType, mapOf("id" to 1)),
                                createEngineObjectData(bazType, mapOf("id" to 2)),
                            )
                        }
                    }
                }
                type("Baz") {
                    checker {
                        objectSelections("key", "id")
                        fn { _, objectDataMap ->
                            val eod = objectDataMap["key"]!!
                            if (eod.fetch("id") == 2) throw IllegalAccessException("permission denied")
                        }
                    }
                }
            },
            engineConfig = engineConfig
        ) { runQuery ->
            val result = runQuery("{ bazList { id }}")
            assertEquals(
                mapOf(
                    "bazList" to listOf(
                        mapOf("id" to "1"),
                        null
                    )
                ),
                result.getData()
            )
            assertEquals(1, result.errors.size)
            val error = result.errors[0]
            assertEquals(listOf("bazList", 1), error.path)
            assertTrue(error.message.contains("permission denied"))
            assertEquals("FOO", error.extensions.get("type"))
            assertTrue(reported)
        }
    }

    @Test
    fun `type checker RSS variable resolver can fetch from current object`() {
        runQueryTest(
            MockTenantModuleBootstrapper(
                """
            extend type Query { obj: Obj! }
            type Obj { x: Boolean! }
                """.trimIndent()
            ) {
                field("Query" to "obj") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Obj")!!,
                                mapOf("x" to true)
                            )
                        }
                    }
                }
                type("Obj") {
                    checker {
                        querySelections(
                            "root",
                            "__typename @include(if: \$var)"
                        ) {
                            variables(
                                "var",
                                rss = createRSS("Obj", "x", forChecker = true)
                            ) { ctx, _ ->
                                mapOf("var" to ctx.objectData.getAs<Boolean>("x"))
                            }
                        }
                        fn { _, _ -> }
                    }
                }
            }.toEngineTestModule()
        ) { runQuery ->
            runQuery("{ obj { __typename } }")
                .assertJson(
                    """
                    {
                      data: {
                        obj: {
                          __typename: "Obj"
                        }
                      }
                    }
                    """.trimIndent()
                )
        }
    }

    fun engineConfigWithHandler(reporter: (Throwable) -> Unit): EngineConfiguration {
        val errorReporter = ErrorReporter { exception, _, _ ->
            reporter(exception)
        }
        val errorBuilder = ResolverErrorBuilder { throwable, errorMetadata ->
            listOf(
                ErrorBuilder.newError(errorMetadata)
                    .message(throwable.message ?: "")
                    .extension("type", "FOO")
                    .build()
            )
        }
        return EngineConfiguration.featureTestDefault.copy(
            dataFetcherExceptionHandler = ViaductDataFetcherExceptionHandler(
                errorReporter = errorReporter,
                errorBuilder = errorBuilder
            )
        )
    }
}
