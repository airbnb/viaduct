package viaduct.tenant.codegen.kotlingen.bytecode

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.codegen.st.STContents
import viaduct.graphql.schema.ViaductExtendedSchema

class UnionGenTest {
    private fun genUnion(
        sdl: String,
        typename: String
    ): STContents {
        val schema = mkSchema(sdl)
        val builder = mkKotlinGRTFilesBuilder(schema)
        val def = schema.types[typename]!! as ViaductExtendedSchema.Union
        return builder.unionKotlinGen(def)
    }

    @Test
    fun `generates Reflection`() {
        val result = genUnion(
            """
                type Query { empty: Int }
                union Union = Query
            """.trimIndent(),
            "Union"
        ).toString()
        assertTrue(result.contains("object Reflection : viaduct.api.reflect.Type<pkg.Union>"))
        assertTrue(result.contains("object Fields"))
    }
}
