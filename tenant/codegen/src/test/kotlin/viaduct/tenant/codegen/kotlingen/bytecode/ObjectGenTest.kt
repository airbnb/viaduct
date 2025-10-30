package viaduct.tenant.codegen.kotlingen.bytecode

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.codegen.st.STContents
import viaduct.graphql.schema.ViaductSchema

class ObjectGenTest {
    private fun genObject(
        sdl: String,
        typename: String
    ): STContents {
        val schema = mkSchema(sdl)
        val builder = mkKotlinGRTFilesBuilder(schema)
        return builder.objectKotlinGen(schema.types[typename]!! as ViaductSchema.Object)
    }

    @Test
    fun `generates Reflection`() {
        val result = genObject("type Query { x: Int }", "Query").toString()
        assertTrue(result.contains("object Reflection : viaduct.api.reflect.Type<pkg.Query>"))
        assertTrue(result.contains("object Fields"))
    }

    @Test
    fun `generates toBuilder method`() {
        val sdl = """
            interface Node { id: ID! }
            type Query { dummy: Int }
            type User implements Node { id: ID! name: String }
        """.trimIndent()
        val result = genObject(sdl, "User").toString()
        assertTrue(result.contains("fun toBuilder(): Builder ="))
        assertTrue(result.contains("Builder(context, engineObject.graphQLObjectType, toBuilderEOD())"))
    }

    @Test
    fun `generates Builder with two constructors`() {
        val sdl = """
            interface Node { id: ID! }
            type Query { dummy: Int }
            type User implements Node { id: ID! name: String }
        """.trimIndent()
        val result = genObject(sdl, "User").toString()
        // Public constructor
        assertTrue(result.contains("constructor(context: ExecutionContext)"))
        // Internal constructor for toBuilder
        assertTrue(result.contains("internal constructor("))
        assertTrue(result.contains("context: InternalContext"))
        assertTrue(result.contains("graphQLObjectType: graphql.schema.GraphQLObjectType"))
        assertTrue(result.contains("baseEngineObjectData: EngineObjectData"))
    }
}
