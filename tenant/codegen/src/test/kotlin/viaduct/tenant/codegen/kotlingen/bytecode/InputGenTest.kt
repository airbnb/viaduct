package viaduct.tenant.codegen.kotlingen.bytecode

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.codegen.st.STContents
import viaduct.graphql.schema.ViaductExtendedSchema

class InputGenTest {
    private fun genInput(
        sdl: String,
        typename: String
    ): STContents {
        val schema = mkSchema(sdl)
        val builder = mkKotlinGRTFilesBuilder()
        val def = schema.types[typename]!! as ViaductExtendedSchema.Input
        val desc = InputTypeDescriptor(def.name, def.fields, def)
        return builder.inputKotlinGen(desc, "viaduct.api.types.Input")
    }

    @Test
    fun `generates Reflection`() {
        val result = genInput(
            """
                type Query { empty: Int }
                input Input { x: Int, y: Input }
            """.trimIndent(),
            "Input"
        ).toString()
        assertTrue(result.contains("object Reflection : viaduct.api.reflect.Type<pkg.Input>"))
        assertTrue(result.contains("object Fields"))
    }
}
