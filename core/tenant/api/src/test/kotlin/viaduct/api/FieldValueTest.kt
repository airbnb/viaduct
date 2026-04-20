package viaduct.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FieldValueTest {
    @Test
    fun `test ofValue`() {
        val fv = FieldValue.ofValue(5)
        assertEquals(5, fv.get())
        assertFalse(fv.isError)
    }

    @Test
    fun `test ofError`() {
        val error = Exception("Error msg")
        val fv = FieldValue.ofError(error)
        val thrown = assertThrows<Exception> { fv.get() }
        assertEquals(error, thrown)
        assertTrue(fv.isError)
    }
}
