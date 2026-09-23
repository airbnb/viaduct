package viaduct.tenant.codegen.bytecode.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccessorFormTest {
    @Test
    fun `each accessor form keeps its suffix, fetch method, and nullability`() {
        assertEquals("OrThrow", AccessorForm.STRICT.suffix)
        assertEquals("getInternal", AccessorForm.STRICT.fetchMethod)
        assertFalse(AccessorForm.STRICT.nullable)

        assertEquals("", AccessorForm.SOFT.suffix)
        assertEquals("getOrNullInternal", AccessorForm.SOFT.fetchMethod)
        assertTrue(AccessorForm.SOFT.nullable)
    }

    @Test
    fun `a field has exactly two accessor forms`() {
        assertEquals(listOf("OrThrow", ""), AccessorForm.entries.map { it.suffix })
    }
}
