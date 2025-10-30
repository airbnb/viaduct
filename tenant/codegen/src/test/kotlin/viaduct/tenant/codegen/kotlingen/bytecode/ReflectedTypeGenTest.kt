package viaduct.tenant.codegen.kotlingen.bytecode

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.codegen.st.STContents
import viaduct.graphql.schema.ViaductSchema

class ReflectedTypeGenTest {
    private fun genType(
        sdl: String,
        typename: String
    ): STContents {
        val schema = mkSchema(sdl)
        val builder = mkKotlinGRTFilesBuilder(schema)
        return builder.reflectedTypeGen(schema.types[typename]!! as ViaductSchema.Object)
    }

    @Test
    fun simple() {
        val result = genType("type Query { x: Int }", "Query").toString()
        assertTrue(result.contains("object Reflection : viaduct.api.reflect.Type<pkg.Query>"))
    }
}
