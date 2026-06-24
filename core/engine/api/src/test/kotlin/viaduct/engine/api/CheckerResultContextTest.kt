package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CheckerResultContextTest {
    @Test
    fun `fieldDirectives defaults to null`() {
        assertNull(CheckerResultContext().fieldDirectives)
    }

    @Test
    fun `fieldDirectives preserves provided directive context`() {
        val directives = object : FieldDirectives {
            override fun hasDirective(
                name: String,
                args: ((Map<String, Any?>) -> Boolean)?,
            ): Boolean {
                if (name != "testDirective") return false
                return args?.invoke(mapOf("enabled" to true)) ?: true
            }
        }

        val context = CheckerResultContext(fieldDirectives = directives)
        val fieldDirectives = context.fieldDirectives!!

        assertSame(directives, fieldDirectives)
        assertTrue(fieldDirectives.hasDirective("testDirective"))
        assertTrue(fieldDirectives.hasDirective("testDirective") { args -> args["enabled"] == true })
        assertFalse(fieldDirectives.hasDirective("unknownDirective"))
    }
}
