package viaduct.service.api

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class SchemaIdTest {

    @Test
    fun `SchemaId_Scoped class no longer exists`() {
        assertThrows<ClassNotFoundException> {
            Class.forName("viaduct.service.api.SchemaId\$Scoped")
        }
    }

    @Test
    fun `SchemaId equality is based on id`() {
        assertEquals(SchemaId("admin"), SchemaId("admin"))
        assertFalse(SchemaId("admin") == SchemaId("public"))
    }

    @Test
    fun `SchemaId hashCode is based on id`() {
        assertEquals(SchemaId("admin").hashCode(), SchemaId("admin").hashCode())
    }

    @Test
    fun `SchemaId Full and None are accessible`() {
        assertEquals("FULL", SchemaId.Full.id)
        assertEquals("NONE", SchemaId.None.id)
    }

    @Test
    fun `SchemaId Full equals SchemaId with FULL id`() {
        assertEquals(SchemaId.Full, SchemaId("FULL"))
    }

    @Test
    fun `SchemaId can be constructed with custom id`() {
        val id = SchemaId("my-scope")
        assertEquals("my-scope", id.id)
    }

    @Test
    fun `SchemaId toString includes id`() {
        assertTrue(SchemaId("my-scope").toString().contains("my-scope"))
    }
}
