package viaduct.engine.api.mocks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.select.SelectionsParser

class MockRequiredSelectionSetRegistryTest {
    @Test
    fun `empty`() {
        val reg = MockRequiredSelectionSetRegistry.empty
        val noEntries = emptyList<RequiredSelectionSet>()
        assertEquals(noEntries, reg.getRequiredSelectionSetsForField("Foo", "x"))
        assertEquals(noEntries, reg.getFieldResolverRequiredSelectionSets("Foo", "y"))
        assertEquals(noEntries, reg.getRequiredSelectionSetsForType("Foo"))
    }

    @Test
    fun `fieldResolverEntry`() {
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntry("Foo" to "f1", "x")
            .fieldResolverEntry("Foo" to "f2", "x y")
            .fieldResolverEntry("Bar" to "b1", "x")
            .build()

        assertRequiredSelectionSetListEquals(
            listOf(
                RequiredSelectionSet(
                    SelectionsParser.parse("Foo", "x"),
                    emptyList(),
                    forChecker = false
                )
            ),
            reg.getRequiredSelectionSetsForField("Foo", "f1")
        )

        assertRequiredSelectionSetListEquals(
            listOf(
                RequiredSelectionSet(
                    SelectionsParser.parse("Foo", "x y"),
                    emptyList(),
                    forChecker = false
                )
            ),
            reg.getRequiredSelectionSetsForField("Foo", "f2")
        )
        assertRequiredSelectionSetListEquals(
            listOf(
                RequiredSelectionSet(
                    SelectionsParser.parse("Bar", "x"),
                    emptyList(),
                    forChecker = false
                )
            ),
            reg.getRequiredSelectionSetsForField("Bar", "b1")
        )
    }

    @Test
    fun `fieldResolverEntry -- with variables`() {
        val variableResolver = VariablesResolver.fromSelectionSetVariables(
            SelectionsParser.parse("Foo", "b"),
            ParsedSelections.empty("Query"),
            listOf(FromObjectFieldVariable("a", "b")),
            forChecker = false
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntry(
                "Foo" to "f2",
                "x(arg:\$a), b",
                variableResolver
            )
            .build()

        val rssWithVariable = RequiredSelectionSet(
            SelectionsParser.parse("Foo", "x(arg:\$a), b"),
            variableResolver,
            forChecker = false
        )
        assertRequiredSelectionSetListEquals(
            listOf(rssWithVariable),
            reg.getRequiredSelectionSetsForField("Foo", "f2")
        )
    }

    @Test
    fun `typeCheckerEntry`() {
        val reg = MockRequiredSelectionSetRegistry.builder()
            .typeCheckerEntry("Bar", "x")
            .build()

        assertRequiredSelectionSetListEquals(
            listOf(
                RequiredSelectionSet(
                    SelectionsParser.parse("Bar", "x"),
                    emptyList(),
                    forChecker = true
                )
            ),
            reg.getRequiredSelectionSetsForType("Bar")
        )
    }

    @Test
    fun `typeCheckerEntry -- with variables`() {
        val variableResolver = VariablesResolver.fromSelectionSetVariables(
            SelectionsParser.parse("Foo", "b"),
            ParsedSelections.empty("Query"),
            listOf(FromObjectFieldVariable("a", "b")),
            forChecker = true
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .typeCheckerEntry(
                "Foo",
                "x(arg:\$a), b",
                variableResolver
            )
            .build()

        assertRequiredSelectionSetListEquals(
            listOf(
                RequiredSelectionSet(
                    SelectionsParser.parse("Foo", "x(arg:\$a), b"),
                    variableResolver,
                    forChecker = true
                )
            ),
            reg.getRequiredSelectionSetsForType("Foo")
        )
    }

    @Test
    fun `fieldResolverEntryForType`() {
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntryForType(
                "Query",
                "Foo" to "f1",
                "x",
            )
            .build()

        val rsss = reg.getRequiredSelectionSetsForField("Foo", "f1")
        assertEquals(1, rsss.size)
        val rss = rsss.first()
        assertEquals("Query", rss.selections.typeName)
    }

    @Test
    fun `plus`() {
        val a = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntry("Foo" to "a", "x")
            .fieldCheckerEntry("Foo" to "b", "y")
            .fieldResolverEntryForType("Query", "Foo" to "b", "y")
            .typeCheckerEntry("Foo", "x")
            .build()
        val b = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntry("Foo" to "b", "y2")
            .fieldCheckerEntry("Foo" to "c", "z")
            .typeCheckerEntry("Foo", "z")
            .build()

        val result = a + b
        assertRequiredSelectionSetListEquals(
            listOf(
                RequiredSelectionSet(
                    SelectionsParser.parse("Foo", "x"),
                    emptyList(),
                    forChecker = false
                )
            ),
            result.getRequiredSelectionSetsForField("Foo", "a")
        )

        val resultForB = result.getRequiredSelectionSetsForField("Foo", "b")
        val expectedForB = listOf(
            RequiredSelectionSet(
                SelectionsParser.parse("Foo", "y"),
                emptyList(),
                forChecker = true
            ),
            RequiredSelectionSet(
                SelectionsParser.parse("Query", "y"),
                emptyList(),
                forChecker = false
            ),
            RequiredSelectionSet(
                SelectionsParser.parse("Foo", "y2"),
                emptyList(),
                forChecker = false
            )
        )
        assertTrue(containsAllRss(expectedForB, resultForB), "Result should contain all expected selection sets for field b")

        assertRequiredSelectionSetListEquals(
            listOf(
                RequiredSelectionSet(
                    SelectionsParser.parse("Foo", "z"),
                    emptyList(),
                    forChecker = true
                )
            ),
            result.getRequiredSelectionSetsForField("Foo", "c")
        )

        val resultForType = result.getRequiredSelectionSetsForType("Foo")
        val expectedForType = listOf(
            RequiredSelectionSet(
                SelectionsParser.parse("Foo", "x"),
                emptyList(),
                forChecker = true
            ),
            RequiredSelectionSet(
                SelectionsParser.parse("Foo", "z"),
                emptyList(),
                forChecker = true
            )
        )
        assertTrue(containsAllRss(expectedForType, resultForType), "Result should contain all expected selection sets for type Foo")

        assertEquals(
            listOf<RequiredSelectionSet>(),
            result.getRequiredSelectionSetsForField("Missing", "a")
        )
    }

    private fun containsAllRss(
        expected: List<RequiredSelectionSet>,
        actual: List<RequiredSelectionSet>
    ): Boolean {
        return expected.all { expectedRss ->
            actual.any { actualRss -> actualRss.isEqualTo(expectedRss) }
        }
    }
}
