package viaduct.engine.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.FromArgument
import viaduct.engine.api.FromFieldVariablesResolver
import viaduct.engine.api.mocks.MockCheckerExecutor
import viaduct.engine.api.mocks.MockVariablesResolver
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.spi.CheckerExecutor

class CheckerVariablesExtractionTest {
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
