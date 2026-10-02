package viaduct.tenant.codegen.kotlingen.bytecode

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.codegen.st.STContents
import viaduct.graphql.schema.ViaductSchema

class EnumGenTest {
    private fun genEnum(
        sdl: String,
        typename: String
    ): STContents {
        val schema = mkSchema(sdl)
        val builder = mkKotlinGRTFilesBuilder(schema)
        return builder.enumKotlinGen(schema.types[typename]!! as ViaductSchema.Enum)
    }

    @Test
    fun `generates Reflection`() {
        val result = genEnum(
            """
                type Query { empty: Int }
                enum Enum { X }
            """.trimIndent(),
            "Enum"
        ).toString()
        assertTrue(result.contains("object Reflection : viaduct.api.reflect.Type<pkg.Enum>"))
        assertFalse(result.contains("object Fields"))
    }

    @Test
    fun `escapes values that are Kotlin keywords`() {
        val result = genEnum(
            """
                type Query { empty: Int }
                enum Enum { if class new NORMAL }
            """.trimIndent(),
            "Enum"
        ).toString()
        assertTrue(result.contains("`if`,"))
        assertTrue(result.contains("`class`,"))
        // "new" is a Java keyword but not a Kotlin one, so it needs no escaping here.
        assertTrue(result.contains("new,"))
        assertFalse(result.contains("`new`"))
    }
}
