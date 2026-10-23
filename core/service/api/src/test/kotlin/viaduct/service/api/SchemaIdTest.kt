@file:OptIn(viaduct.apiannotations.InternalApi::class)

package viaduct.service.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class SchemaIdTest {
    @Test
    fun `scoped schema keeps its name and scopes`() {
        val schemaId = SchemaId.Scoped("host", setOf("listings", "pricing"))

        assertEquals("host", schemaId.id)
        assertEquals(setOf("listings", "pricing"), schemaId.scopeIds)
    }

    @Test
    fun `scoped schema requires at least one scope`() {
        assertThrows(IllegalArgumentException::class.java) {
            SchemaId.Scoped("host", emptySet())
        }
    }
}
