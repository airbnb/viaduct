package viaduct.api.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.mkRawSelectionSet
import viaduct.engine.api.select.SelectionsParser

class KeyMappingTest {
    @Test
    fun `KeyMapping -- Map -- Identity`() {
        val m = KeyMapping.Map.Identity
        assertEquals(listOf("key"), m.forward("key"))
        assertEquals(listOf("key"), m.invert("key"))
    }

    @Test
    fun `KeyMapping_map -- throws IllegalArgumentSelection when selectionSet required by KeyMapping but not supplied`() {
        assertDoesNotThrow {
            KeyMapping.map(KeyMapping.SelectionToSelection, null)
            KeyMapping.map(KeyMapping.FieldNameToFieldName, null)
        }

        assertThrows<IllegalArgumentException> {
            KeyMapping.map(
                KeyMapping(KeyMapping.KeyType.Selection, KeyMapping.KeyType.FieldName),
                null
            )
        }

        assertThrows<IllegalArgumentException> {
            KeyMapping.map(
                KeyMapping(KeyMapping.KeyType.FieldName, KeyMapping.KeyType.Selection),
                null
            )
        }
    }

    @Test
    fun `KeyMapping_map -- generates maps for selections`() {
        val ss = mkRawSelectionSet(
            SelectionsParser.parse("Obj", "a, b1:b, b2:b"),
            MockSchema.mk("type Obj { a:Int, b: Int, c: Obj }"),
            emptyMap()
        )

        val map = KeyMapping.map(KeyMapping.FieldNameToSelection, ss)

        // a
        assertEquals(listOf("a"), map.forward("a"))

        // b1:b, b2:b
        assertEquals(listOf("b1", "b2"), map.forward("b"))
        assertEquals(listOf("b"), map.invert("b1"))
        assertEquals(listOf("b"), map.invert("b2"))
    }
}
