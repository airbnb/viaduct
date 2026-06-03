package viaduct.remote

import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

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
        assertFailsWith<UnsupportedOperationException> { empty.addVariables(emptyMap()) }
        assertFailsWith<UnsupportedOperationException> { empty.toNodelikeSelectionSet("node", emptyList()) }
        assertFailsWith<IllegalArgumentException> { empty.resolveSelection("Film", "title") }
    }
}
