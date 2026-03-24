package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ParsedSelectionsTest {
    @Test
    fun `empty`() {
        val ps = ParsedSelections.empty("Query")
        assertTrue(ps.selections.selections.isEmpty())
        assertTrue(ps.fragmentMap.isEmpty())
        assertTrue(ps.toDocument().definitions.isEmpty())
        assertNull(ps.filterToPath(emptyList()))
    }
}
