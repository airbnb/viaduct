@file:Suppress("ForbiddenImport")

package viaduct.remote.registry

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockSchema

class SchemaRegistryTest {
    @AfterEach
    fun cleanup() {
        SchemaRegistry.clear()
    }

    @Test
    fun `register publishes the schema and clear unpublishes it`() {
        assertNull(SchemaRegistry.get())
        assertFalse(SchemaRegistry.isRegistered())

        val schema = MockSchema.mk("extend type Query { ping: String }")
        SchemaRegistry.register(schema)
        assertSame(schema, SchemaRegistry.get())
        assertTrue(SchemaRegistry.isRegistered())

        SchemaRegistry.clear()
        assertNull(SchemaRegistry.get())
        assertFalse(SchemaRegistry.isRegistered())
    }
}
