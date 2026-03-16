package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.select.SelectionsParser

class RequiredSelectionSetsTest {
    @Test
    fun `empty returns instance with null objectSelections and null querySelections`() {
        val empty = RequiredSelectionSets.empty()
        assertNull(empty.objectSelections)
        assertNull(empty.querySelections)
    }

    @Test
    fun `empty returns the same singleton instance`() {
        assertSame(RequiredSelectionSets.empty(), RequiredSelectionSets.empty())
    }

    @Test
    fun `can construct with non-null selections`() {
        val objectSelections = RequiredSelectionSet(
            SelectionsParser.parse("Query", "x"),
            emptyList(),
            forChecker = false,
        )
        val querySelections = RequiredSelectionSet(
            SelectionsParser.parse("Query", "y"),
            emptyList(),
            forChecker = false,
        )
        val rss = RequiredSelectionSets(objectSelections = objectSelections, querySelections = querySelections)
        assertSame(objectSelections, rss.objectSelections)
        assertSame(querySelections, rss.querySelections)
    }

    @Test
    fun `data class equality works`() {
        val a = RequiredSelectionSets(objectSelections = null, querySelections = null)
        val b = RequiredSelectionSets(objectSelections = null, querySelections = null)
        assertEquals(a, b)
    }
}
