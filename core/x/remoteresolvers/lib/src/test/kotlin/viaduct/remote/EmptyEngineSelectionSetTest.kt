package viaduct.remote

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EmptyEngineSelectionSetTest {
    private val empty = EmptyEngineSelectionSet("Film")

    @Test
    fun `reports empty and never claims to contain anything`() {
        assertTrue(empty.isEmpty())
        assertTrue(empty.isTransitivelyEmpty())
        assertTrue(empty.selections().isEmpty())
        assertFalse(empty.containsField("Film", "title"))
        assertFalse(empty.containsSelection("Film", "title"))
        assertSame(empty, empty.selectionSetForType("Film"))
    }

    @Test
    fun `unsupported operations fail with explicit messages`() {
        assertThrows<UnsupportedOperationException> { empty.addVariables(emptyMap()) }
        assertThrows<UnsupportedOperationException> { empty.toNodelikeSelectionSet("node", emptyList()) }
        assertThrows<IllegalArgumentException> { empty.resolveSelection("Film", "title") }
    }
}
