package viaduct.tenant.codegen.kotlingen.bytecode

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.codegen.st.STContents
import viaduct.graphql.schema.ViaductExtendedSchema

class ObjectGenTest {
    private fun genObject(
        sdl: String,
        typename: String
    ): STContents {
        val schema = mkSchema(sdl)
        val builder = mkKotlinGRTFilesBuilder(schema)
        return builder.objectKotlinGen(schema.types[typename]!! as ViaductExtendedSchema.Object)
    }

    @Test
    fun `generates Reflection`() {
        val result = genObject("type Query { x: Int }", "Query").toString()
        assertTrue(result.contains("object Reflection : viaduct.api.reflect.Type<pkg.Query>"))
        assertTrue(result.contains("object Fields"))
    }
}
