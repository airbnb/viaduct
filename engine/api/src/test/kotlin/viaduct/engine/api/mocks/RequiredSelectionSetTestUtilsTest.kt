package viaduct.engine.api.mocks

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.select.SelectionsParser

class RequiredSelectionSetTestUtilsTest {
    @Test
    fun `isEqualTo returns true for identical selections and properties`() {
        val rss1 = RequiredSelectionSet(
            SelectionsParser.parse("Foo", "x y"),
            emptyList(),
            forChecker = false
        )
        val rss2 = RequiredSelectionSet(
            SelectionsParser.parse("Foo", "x y"),
            emptyList(),
            forChecker = false
        )

        assert(rss1.isEqualTo(rss2))
        assert(rss2.isEqualTo(rss1))
        assertDoesNotThrow {
            assertRequiredSelectionSetEquals(rss1, rss2)
        }
    }

    @Test
    fun `returns false and throws for different selections`() {
        val rss1 = RequiredSelectionSet(
            SelectionsParser.parse("Foo", "x"),
            emptyList(),
            forChecker = false
        )
        val rss2 = RequiredSelectionSet(
            SelectionsParser.parse("Foo", "y"),
            emptyList(),
            forChecker = false
        )

        assert(!rss1.isEqualTo(rss2))
        assertThrows(AssertionError::class.java) {
            assertRequiredSelectionSetEquals(rss1, rss2)
        }
    }

    @Test
    fun `returns false and throws for different forChecker flag`() {
        val rss1 = RequiredSelectionSet(
            SelectionsParser.parse("Foo", "x"),
            emptyList(),
            forChecker = false
        )
        val rss2 = RequiredSelectionSet(
            SelectionsParser.parse("Foo", "x"),
            emptyList(),
            forChecker = true
        )

        assert(!rss1.isEqualTo(rss2))
        assertThrows(AssertionError::class.java) {
            assertRequiredSelectionSetEquals(rss1, rss2)
        }
    }

    @Test
    fun `returns false and throws for different variablesResolvers`() {
        val variableResolver = VariablesResolver.fromSelectionSetVariables(
            SelectionsParser.parse("Foo", "b"),
            ParsedSelections.empty("Query"),
            listOf(FromObjectFieldVariable("a", "b")),
            forChecker = false
        )

        val rss1 = RequiredSelectionSet(
            SelectionsParser.parse("Foo", "x"),
            emptyList(),
            forChecker = false
        )
        val rss2 = RequiredSelectionSet(
            SelectionsParser.parse("Foo", "x"),
            variableResolver,
            forChecker = false
        )

        assert(!rss1.isEqualTo(rss2))
        assertThrows(AssertionError::class.java) {
            assertRequiredSelectionSetEquals(rss1, rss2)
        }
    }

    @Test
    fun `returns false when comparing with null`() {
        val rss = RequiredSelectionSet(
            SelectionsParser.parse("Foo", "x"),
            emptyList(),
            forChecker = false
        )

        assert(!rss.isEqualTo(null))
        assertThrows(AssertionError::class.java) {
            assertRequiredSelectionSetEquals(rss, null)
        }
    }

    @Test
    fun `assertRequiredSelectionSetEquals succeeds when both are null`() {
        assertDoesNotThrow {
            assertRequiredSelectionSetEquals(null, null)
        }
    }

    @Test
    fun `assertRequiredSelectionSetEquals throws when expected is null but actual is not`() {
        val rss = RequiredSelectionSet(
            SelectionsParser.parse("Foo", "x"),
            emptyList(),
            forChecker = false
        )

        assertThrows(AssertionError::class.java) {
            assertRequiredSelectionSetEquals(null, rss)
        }
    }

    @Test
    fun `assertRequiredSelectionSetListEquals succeeds for equal lists`() {
        val list1 = listOf(
            RequiredSelectionSet(SelectionsParser.parse("Foo", "x"), emptyList(), forChecker = false),
            RequiredSelectionSet(SelectionsParser.parse("Bar", "y"), emptyList(), forChecker = true)
        )
        val list2 = listOf(
            RequiredSelectionSet(SelectionsParser.parse("Foo", "x"), emptyList(), forChecker = false),
            RequiredSelectionSet(SelectionsParser.parse("Bar", "y"), emptyList(), forChecker = true)
        )

        assertDoesNotThrow {
            assertRequiredSelectionSetListEquals(list1, list2)
        }
    }

    @Test
    fun `assertRequiredSelectionSetListEquals throws for different sized lists`() {
        val list1 = listOf(
            RequiredSelectionSet(SelectionsParser.parse("Foo", "x"), emptyList(), forChecker = false)
        )
        val list2 = listOf(
            RequiredSelectionSet(SelectionsParser.parse("Foo", "x"), emptyList(), forChecker = false),
            RequiredSelectionSet(SelectionsParser.parse("Bar", "y"), emptyList(), forChecker = true)
        )

        val error = assertThrows(AssertionError::class.java) {
            assertRequiredSelectionSetListEquals(list1, list2)
        }
        assert(error.message?.contains("List sizes differ") == true)
    }

    @Test
    fun `assertRequiredSelectionSetListEquals throws for lists with different elements`() {
        val list1 = listOf(
            RequiredSelectionSet(SelectionsParser.parse("Foo", "x"), emptyList(), forChecker = false),
            RequiredSelectionSet(SelectionsParser.parse("Bar", "y"), emptyList(), forChecker = true)
        )
        val list2 = listOf(
            RequiredSelectionSet(SelectionsParser.parse("Foo", "x"), emptyList(), forChecker = false),
            RequiredSelectionSet(SelectionsParser.parse("Bar", "z"), emptyList(), forChecker = true)
        )

        val error = assertThrows(AssertionError::class.java) {
            assertRequiredSelectionSetListEquals(list1, list2)
        }
        assert(error.message?.contains("RequiredSelectionSets differ at index") == true)
    }

    @Test
    fun `assertRequiredSelectionSetListEquals succeeds for empty lists`() {
        assertDoesNotThrow {
            assertRequiredSelectionSetListEquals(emptyList(), emptyList())
        }
    }
}
