package viaduct.graphql.schema.scopes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScopeModeTest {

    // ScopeMode contract
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

    // Bytecode check: project targets JVM 17 (major version 61)
    // Spec noted JVM 11 (55) but the conventions.kotlin plugin uses javaToolchain 17
    @Test
    fun `ScopeMode class file targets expected JVM version`() {
        assertClassFileMajorVersion(ScopeMode::class.java, 61)
    }

    @Test
    fun `ScopedMode class file targets expected JVM version`() {
        assertClassFileMajorVersion(ScopedMode::class.java, 61)
    }

    @Test
    fun `NoScopesMode class file targets expected JVM version`() {
        assertClassFileMajorVersion(NoScopesMode::class.java, 61)
    }

    private fun assertClassFileMajorVersion(clazz: Class<*>, expectedMajor: Int) {
        val resourcePath = "/" + clazz.name.replace('.', '/') + ".class"
        val bytes = clazz.getResourceAsStream(resourcePath)!!.use { it.readBytes() }
        // Class file bytes 6-7 (0-indexed) hold major version in big-endian
        val major = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        assertEquals(expectedMajor, major, "Expected major version $expectedMajor for ${clazz.simpleName}, got $major")
    }
}
