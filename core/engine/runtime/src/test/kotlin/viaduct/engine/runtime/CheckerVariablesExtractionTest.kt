package viaduct.engine.runtime

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.FromArgument
import viaduct.engine.api.FromFieldVariablesResolver
import viaduct.engine.api.mocks.MockCheckerExecutor
import viaduct.engine.api.mocks.MockVariablesResolver
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.VariableFromFunctionDefinitions
import viaduct.engine.runtime.mocks.ContextMocks

@OptIn(InternalApi::class)
class CheckerVariablesExtractionTest {
    @Test
    fun `retains callback identity and validates its complete output before projection`() =
        runTest {
            val required = createRSS(
                "Query",
                "policy(prefix: \$prefix, other: \$onlyOther)",
                variableProviders = listOf(
                    MockVariablesResolver("onlyOther") { _, _ -> error("Pruned provider must not run") },
                    MockVariablesResolver("prefix", "unused") { _, _ -> mapOf("prefix" to "selected", "unused" to true) },
                ),
                forChecker = true,
            )
            val original = requireNotNull(extractCheckerVariableDefinitions(mapOf("input" to required)).getValue("input").fromFunction)

            val retained = requireNotNull(original.retainCheckerVariables(setOf("prefix")))

            assertEquals(setOf("prefix"), retained.variableNames)
            assertEquals(mapOf("prefix" to "selected"), invokeProvider(retained))
            assertEquals(setOf("prefix", "onlyOther"), original.variableNames)
            assertNull(original.retainCheckerVariables(emptySet()))
        }

    @Test
    fun `retained callback must return even its pruned output keys`() =
        runTest {
            listOf(
                mapOf("prefix" to "selected"),
                mapOf("prefix" to "selected", "onlyOther" to true, "unexpected" to true),
            ).forEach { invalidValues ->
                val required = createRSS(
                    "Query",
                    "policy(prefix: \$prefix, other: \$onlyOther)",
                    variableProviders = listOf(MockVariablesResolver("prefix", "onlyOther") { _, _ -> invalidValues }),
                    forChecker = true,
                )
                val original = requireNotNull(extractCheckerVariableDefinitions(mapOf("input" to required)).getValue("input").fromFunction)
                val retained = requireNotNull(original.retainCheckerVariables(setOf("prefix")))

                val failure = assertThrows<IllegalStateException> { invokeProvider(retained) }

                assertTrue(failure.message.orEmpty().contains("returned invalid variables"))
            }
        }

    @Test
    fun `an opaque provider remains indivisible when any declared variable survives`() {
        val provider = object : VariableFromFunctionDefinitions {
            override val variableNames = setOf("prefix", "onlyOther")

            override suspend fun provideVariables(
                objectData: EngineObjectData.Sync,
                arguments: Map<String, Any?>,
                context: EngineExecutionContext,
            ): Map<String, Any?> = error("Selecting a provider must not invoke it")
        }

        assertSame(provider, provider.retainCheckerVariables(setOf("prefix")))
        assertNull(provider.retainCheckerVariables(setOf("unrelated")))
    }

    private suspend fun invokeProvider(provider: VariableFromFunctionDefinitions): Map<String, Any?> {
        val schema = createSchemaWithWiring("extend type Query { value: String }")
        return provider.provideVariables(
            createEngineObjectData(schema.schema.queryType, emptyMap()),
            emptyMap(),
            ContextMocks(myFullSchema = schema).engineExecutionContext,
        )
    }

    @Test
    fun `extracts variables referenced only inside named fragments`() {
        val required =
            createRSS(
                typeName = "Item",
                selectionString =
                    """
                    fragment Main on Item { ...PolicyInput }
                    fragment PolicyInput on Item { policy(argument: ${'$'}argument) }
                    """.trimIndent(),
                variableProviders = listOf(FromArgument("argument", listOf("arg"))),
                forChecker = true,
            )

        val extracted = extractCheckerVariableDefinitions(mapOf("input" to required))

        assertEquals(mapOf("argument" to "arg"), extracted.getValue("input").fromArguments.variables)
    }

    @Test
    fun `follows transitive spreads without extracting variables from unused fragments`() {
        val required =
            createRSS(
                typeName = "Item",
                selectionString =
                    """
                    fragment Main on Item { ...PolicyInput }
                    fragment PolicyInput on Item { ...NestedPolicyInput }
                    fragment NestedPolicyInput on Item { policy(argument: ${'$'}argument) }
                    fragment Unused on Item { policy(argument: ${'$'}unused) }
                    """.trimIndent(),
                variableProviders =
                    listOf(
                        FromArgument("argument", listOf("arg")),
                        FromArgument("unused", listOf("unusedArg")),
                    ),
                forChecker = true,
            )

        val extracted = extractCheckerVariableDefinitions(mapOf("input" to required))

        assertEquals(mapOf("argument" to "arg"), extracted.getValue("input").fromArguments.variables)
    }

    @Test
    fun `extracts the variable forms supported by runtime2`() {
        val objectDependency = createRSS("Item", "owner", forChecker = true)
        val queryDependency = createRSS("Query", "viewer", forChecker = true)
        val provider =
            MockVariablesResolver("provided", "unused") { _, _ ->
                mapOf("provided" to "value", "unused" to "ignored")
            }
        val required =
            createRSS(
                typeName = "Item",
                selectionString =
                    "policy(argument: \$argument, owner: \$owner, root: \$root, provided: \$provided)",
                variableProviders =
                    listOf(
                        FromArgument("argument", listOf("arg")),
                        FromFieldVariablesResolver("owner", listOf("owner"), objectDependency),
                        FromFieldVariablesResolver("root", listOf("viewer"), queryDependency),
                        provider,
                    ),
                forChecker = true,
            )

        val extracted =
            extractCheckerVariableDefinitions(
                requiredSelectionSets = mapOf("input" to required, "empty" to null),
                objectTypeName = "Item",
                queryTypeName = "Query",
                checkerType = CheckerExecutor.CheckerType.FIELD,
            )

        val definitions = extracted.getValue("input")
        assertEquals(mapOf("argument" to "arg"), definitions.fromArguments.variables)
        assertEquals(mapOf("owner" to "owner"), definitions.fromObjectFields.variables)
        assertEquals(mapOf("root" to "viewer"), definitions.fromQueryFields.variables)
        assertEquals(setOf("provided"), definitions.fromFunction?.variableNames)
        assertSame(ResolverVariableDefinitions.EMPTY, extracted.getValue("empty"))
    }

    @Test
    fun `dispatcher defers unsupported extraction until definitions are read`() {
        val provider =
            MockVariablesResolver(
                "provided",
                requiredSelectionSet = createRSS("Item", "owner", forChecker = true),
            ) { _, _ -> mapOf("provided" to "value") }
        val required =
            createRSS(
                typeName = "Item",
                selectionString = "policy(value: \$provided)",
                variableProviders = listOf(provider),
                forChecker = true,
            )
        val dispatcher =
            CheckerDispatcherImpl(
                checkerExecutor = MockCheckerExecutor(requiredSelectionSets = mapOf("input" to required)),
                objectTypeName = "Item",
                queryTypeName = "Query",
                checkerType = CheckerExecutor.CheckerType.FIELD,
            )

        val failure = assertThrows<IllegalArgumentException> { dispatcher.variableDefinitions }
        assertTrue(failure.message.orEmpty().contains("opaque variables resolver with its own required selection set"))
    }

    @Test
    fun `type checker argument variable is outside the supported extraction forms`() {
        val required =
            createRSS(
                typeName = "Item",
                selectionString = "policy(value: \$argument)",
                variableProviders = listOf(FromArgument("argument", listOf("arg"))),
                forChecker = true,
            )

        val failure =
            assertThrows<IllegalArgumentException> {
                extractCheckerVariableDefinitions(
                    requiredSelectionSets = mapOf("input" to required),
                    objectTypeName = "Item",
                    queryTypeName = "Query",
                    checkerType = CheckerExecutor.CheckerType.TYPE,
                )
            }
        assertTrue(failure.message.orEmpty().contains("Type checker input input"))
    }
}
