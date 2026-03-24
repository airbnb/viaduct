package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.fragment.Fragment

class EngineSelectionSetTest {
    @Test
    fun `empty`() {
        val ss = EngineSelectionSet.empty("Foo")

        assertFalse(ss.containsField(ss.type, "x"))
        assertFalse(ss.containsField("Bar", "x"))

        assertFalse(ss.containsSelection(ss.type, "x"))
        assertFalse(ss.containsSelection("Bar", "x"))

        assertThrows<IllegalArgumentException> {
            ss.resolveSelection(ss.type, "x")
        }

        assertFalse(ss.requestsType(ss.type))
        assertFalse(ss.requestsType("Bar"))

        assertTrue(ss.selectionSetForField(ss.type, "x").isEmpty())
        assertTrue(ss.selectionSetForField("Bar", "x").isEmpty())

        assertTrue(ss.selectionSetForSelection(ss.type, "x").isEmpty())
        assertTrue(ss.selectionSetForSelection("Bar", "x").isEmpty())

        assertTrue(ss.selectionSetForType(ss.type).isEmpty())
        assertTrue(ss.selectionSetForType("Bar").isEmpty())

        assertTrue(ss.isEmpty())

        assertTrue(ss.isTransitivelyEmpty())

        assertNull(ss.argumentsOfSelection(ss.type, "x"))
        assertNull(ss.argumentsOfSelection("Bar", "x"))

        assertTrue(ss.selections().toList().isEmpty())

        assertTrue(ss.toSelectionSet().selections.isEmpty())

        assertEquals(ss.toFragment(), Fragment.empty)

        assertEquals(ss.printAsFieldSet(), "")

        val exceptionToNodelikeSelectionSet = assertThrows<UnsupportedOperationException> {
            ss.toNodelikeSelectionSet("unused", listOf())
        }

        assertEquals("toNodelikeSelectionSet is not supported for EngineSelectionSet.Empty", exceptionToNodelikeSelectionSet.message)

        val exceptionAddVariables = assertThrows<UnsupportedOperationException> {
            ss.addVariables(mapOf())
        }
        assertEquals("addVariables is not supported for EngineSelectionSet.Empty", exceptionAddVariables.message)

        assertEquals("", ss.printAsFieldSet())
    }
}
