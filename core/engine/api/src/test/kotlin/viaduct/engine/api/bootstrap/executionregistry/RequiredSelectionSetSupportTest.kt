package viaduct.engine.api.bootstrap.executionregistry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable

/**
 * Tests for [RequiredSelectionSetSupport] — the language-neutral helper that decodes the build-time
 * execution-registry variable model into engine [viaduct.engine.api.SelectionSetVariable] declarations.
 *
 * The helper is a pure function of its inputs (no schema, no reflection, no suspend), so these tests
 * assert directly on the decoded values rather than on any downstream wiring.
 */
class RequiredSelectionSetSupportTest {
    // ============================================================================
    // toSelectionSetVariable
    // ============================================================================

    @Test
    fun `toSelectionSetVariable -- fromArgument maps to FromArgumentVariable preserving name and path`() {
        val result = RequiredSelectionSetSupport.toSelectionSetVariable(
            ProviderVariablesAPIData(type = "fromArgument", path = "argPath"),
            name = "argVar",
        )
        assertEquals(FromArgumentVariable("argVar", "argPath"), result)
    }

    @Test
    fun `toSelectionSetVariable -- fromObjectField maps to FromObjectFieldVariable preserving name and path`() {
        val result = RequiredSelectionSetSupport.toSelectionSetVariable(
            ProviderVariablesAPIData(type = "fromObjectField", path = "objField"),
            name = "objVar",
        )
        assertEquals(FromObjectFieldVariable("objVar", "objField"), result)
    }

    @Test
    fun `toSelectionSetVariable -- fromQueryField maps to FromQueryFieldVariable preserving name and path`() {
        val result = RequiredSelectionSetSupport.toSelectionSetVariable(
            ProviderVariablesAPIData(type = "fromQueryField", path = "queryField"),
            name = "queryVar",
        )
        assertEquals(FromQueryFieldVariable("queryVar", "queryField"), result)
    }

    @Test
    fun `toSelectionSetVariable -- unknown type throws IllegalStateException naming type and variable`() {
        val exception = assertThrows<IllegalStateException> {
            RequiredSelectionSetSupport.toSelectionSetVariable(
                ProviderVariablesAPIData(type = "fromSomethingElse", path = "p"),
                name = "badVar",
            )
        }
        assertNotNull(exception.message)
        assertTrue(exception.message!!.contains("fromSomethingElse"))
        assertTrue(exception.message!!.contains("badVar"))
    }

    // ============================================================================
    // buildSelectionSetVariables
    // ============================================================================

    @Test
    fun `buildSelectionSetVariables -- both blocks null yields empty list`() {
        assertEquals(
            emptyList<Any>(),
            RequiredSelectionSetSupport.buildSelectionSetVariables(objectSelections = null, querySelections = null),
        )
    }

    @Test
    fun `buildSelectionSetVariables -- block with empty providers yields empty list`() {
        val block = SelectionsBlockConfig(selections = "fragment _ on Query { foo }", variablesProviders = emptyList())
        assertEquals(
            emptyList<Any>(),
            RequiredSelectionSetSupport.buildSelectionSetVariables(objectSelections = block, querySelections = null),
        )
    }

    @Test
    fun `buildSelectionSetVariables -- aggregates providers across object and query blocks`() {
        val objectBlock = SelectionsBlockConfig(
            selections = "fragment _ on Query { obj }",
            variablesProviders = listOf(
                VariableProviderEntryConfig(
                    providedVariables = mapOf("objVar" to "! Int"),
                    providerVariablesAPIData = ProviderVariablesAPIData(type = "fromObjectField", path = "obj"),
                ),
            ),
        )
        val queryBlock = SelectionsBlockConfig(
            selections = "fragment _ on Query { query }",
            variablesProviders = listOf(
                VariableProviderEntryConfig(
                    providedVariables = mapOf("queryVar" to "! Int"),
                    providerVariablesAPIData = ProviderVariablesAPIData(type = "fromQueryField", path = "query"),
                ),
            ),
        )

        val result = RequiredSelectionSetSupport.buildSelectionSetVariables(
            objectSelections = objectBlock,
            querySelections = queryBlock,
        )

        // Object providers come first (object block is concatenated before query block), then query providers.
        assertEquals(
            listOf(
                FromObjectFieldVariable("objVar", "obj"),
                FromQueryFieldVariable("queryVar", "query"),
            ),
            result,
        )
    }

    @Test
    fun `buildSelectionSetVariables -- single provider entry with multiple providedVariables keys expands to one variable per key`() {
        // A single provider entry can declare multiple variable names; each key produces its own
        // SelectionSetVariable, all sharing the entry's providerVariablesAPIData (name + path).
        val block = SelectionsBlockConfig(
            selections = "fragment _ on Query { arg }",
            variablesProviders = listOf(
                VariableProviderEntryConfig(
                    // Use a LinkedHashMap so iteration order is deterministic for the assertion.
                    providedVariables = linkedMapOf("a" to "! Int", "b" to "! String"),
                    providerVariablesAPIData = ProviderVariablesAPIData(type = "fromArgument", path = "arg"),
                ),
            ),
        )

        val result = RequiredSelectionSetSupport.buildSelectionSetVariables(
            objectSelections = block,
            querySelections = null,
        )

        assertEquals(
            listOf(
                FromArgumentVariable("a", "arg"),
                FromArgumentVariable("b", "arg"),
            ),
            result,
        )
    }

    // ============================================================================
    // parseVariableTypeEntries
    // ============================================================================

    @Test
    fun `parseVariableTypeEntries -- parses name and type and trims surrounding whitespace`() {
        assertEquals(
            mapOf("a" to "Int!", "b" to "String"),
            RequiredSelectionSetSupport.parseVariableTypeEntries(listOf("  a :  Int! ", "b: String")),
        )
    }

    @Test
    fun `parseVariableTypeEntries -- ignores blank entries`() {
        assertEquals(
            mapOf("a" to "Int!"),
            RequiredSelectionSetSupport.parseVariableTypeEntries(listOf("", "   ", "\t", "a: Int!")),
        )
    }

    @Test
    fun `parseVariableTypeEntries -- empty input yields empty map`() {
        assertEquals(
            emptyMap<String, String>(),
            RequiredSelectionSetSupport.parseVariableTypeEntries(emptyList()),
        )
    }

    @Test
    fun `parseVariableTypeEntries -- missing colon throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            RequiredSelectionSetSupport.parseVariableTypeEntries(listOf("noColon"))
        }
    }

    @Test
    fun `parseVariableTypeEntries -- empty name throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            RequiredSelectionSetSupport.parseVariableTypeEntries(listOf(": Int!"))
        }
        assertNotNull(exception.message)
        assertTrue(exception.message!!.contains("name is empty"))
    }

    @Test
    fun `parseVariableTypeEntries -- empty type throws IllegalArgumentException`() {
        val exception = assertThrows<IllegalArgumentException> {
            RequiredSelectionSetSupport.parseVariableTypeEntries(listOf("a: "))
        }
        assertNotNull(exception.message)
        assertTrue(exception.message!!.contains("type is empty"))
    }
}
