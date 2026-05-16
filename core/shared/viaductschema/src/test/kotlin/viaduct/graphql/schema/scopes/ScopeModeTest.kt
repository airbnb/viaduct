package viaduct.graphql.schema.scopes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScopeModeTest {

    @Test
    fun `ScopeMode is sealed`() {
        assertTrue(ScopeMode::class.isSealed)
    }

    @Test
    fun `NoScopesMode is singleton`() {
        val a = NoScopesMode
        val b = NoScopesMode
        assertSame(a, b)
    }

    @Test
    fun `NoScopesMode toString is NoScopesMode`() {
        assertEquals("NoScopesMode", NoScopesMode.toString())
    }

    @Test
    fun `ScopedMode data class equality across same-arg instances`() {
        val a = ScopedMode(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf("FULL" to setOf("public"))
        )
        val b = ScopedMode(
            scopeUniverse = setOf("public", "internal"),
            scopedSchemas = mapOf("FULL" to setOf("public"))
        )
        assertEquals(a, b)
    }
}
