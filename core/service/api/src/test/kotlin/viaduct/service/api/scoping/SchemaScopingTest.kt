package viaduct.service.api.scoping

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.apiannotations.ExperimentalApi

@OptIn(ExperimentalApi::class)
class SchemaScopingTest {
    @Test
    fun `EMPTY has no universe, no scoped schemas, and current version`() {
        assertEquals(emptySet(), SchemaScoping.EMPTY.scopeUniverse)
        assertEquals(emptyMap(), SchemaScoping.EMPTY.scopedSchemas)
        assertEquals(SchemaScoping.CURRENT_VERSION, SchemaScoping.EMPTY.version)
    }

    @Test
    fun `isScoped is false when the scope universe is empty`() {
        assertFalse(SchemaScoping.EMPTY.isScoped)
        // A populated scopedSchemas map without a declared universe still reads as unscoped:
        // the universe is the source of truth for whether @scope is in play.
        assertFalse(
            SchemaScoping(
                scopeUniverse = emptySet(),
                scopedSchemas = mapOf("API" to emptySet()),
            ).isScoped,
        )
    }

    @Test
    fun `isScoped is true when the scope universe is non-empty`() {
        assertTrue(
            SchemaScoping(
                scopeUniverse = setOf("public"),
                scopedSchemas = emptyMap(),
            ).isScoped,
        )
        assertTrue(
            SchemaScoping(
                scopeUniverse = setOf("public", "internal"),
                scopedSchemas = mapOf("PUBLIC_API" to setOf("public")),
            ).isScoped,
        )
    }

    @Test
    fun `data class equality compares fields by value`() {
        val a = SchemaScoping(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf("PUBLIC_API" to setOf("public")),
        )
        val b = SchemaScoping(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf("PUBLIC_API" to setOf("public")),
        )
        val different = SchemaScoping(
            scopeUniverse = setOf("public"),
            scopedSchemas = mapOf("PUBLIC_API" to setOf("public")),
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != different)
    }

    @Test
    fun `version defaults to CURRENT_VERSION`() {
        val scoping = SchemaScoping(
            scopeUniverse = setOf("public"),
            scopedSchemas = emptyMap(),
        )
        assertEquals(SchemaScoping.CURRENT_VERSION, scoping.version)
    }

    @Test
    fun `Serializable round-trip preserves all fields`() {
        val original = SchemaScoping(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf(
                "PUBLIC_API" to setOf("public"),
                "FULL_ALIAS" to emptySet(),
            ),
        )

        val bytes = ByteArrayOutputStream().use { bytes ->
            ObjectOutputStream(bytes).use { it.writeObject(original) }
            bytes.toByteArray()
        }

        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
            as SchemaScoping

        assertEquals(original, restored)
        assertEquals(original.isScoped, restored.isScoped)
    }
}
