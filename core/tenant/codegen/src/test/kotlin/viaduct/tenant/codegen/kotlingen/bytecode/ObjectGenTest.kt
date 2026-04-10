package viaduct.tenant.codegen.kotlingen.bytecode

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.codegen.st.STContents
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg

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
    fun `root composite field emitted for root type with composite non-list return`() {
        val sdl = """
            type Foo { name: String }
            type Query { foo(id: ID!): Foo }
        """.trimIndent()
        val result = genObject(sdl, "Query").toString()
        assertTrue(result.contains("${cfg.REFLECTED_ROOT_COMPOSITE_FIELD}"), "Should emit RootCompositeField for root composite field")
        assertTrue(result.contains("${cfg.REFLECTED_ROOT_COMPOSITE_FIELD_IMPL}"), "Should use RootCompositeFieldImpl")
        assertTrue(result.contains("pkg.Query_Foo_Arguments"), "Should include Arguments type")
    }

    @Test
    fun `root composite field with no args uses NoArguments`() {
        val sdl = """
            type Foo { name: String }
            type Query { foo: Foo }
        """.trimIndent()
        val result = genObject(sdl, "Query").toString()
        assertTrue(result.contains("${cfg.REFLECTED_ROOT_COMPOSITE_FIELD}"), "Should emit RootCompositeField for zero-arg root composite field")
        assertTrue(result.contains(cfg.ARGUMENTS_NO_ARGUMENTS.toString().replace('$', '.')), "Should use Arguments.NoArguments for zero-arg field")
    }

    @Test
    fun `root enum field stays CompositeField not RootCompositeField`() {
        val sdl = """
            enum Status { ACTIVE INACTIVE }
            type Query { status: Status }
        """.trimIndent()
        val result = genObject(sdl, "Query").toString()
        assertFalse(result.contains("${cfg.REFLECTED_ROOT_COMPOSITE_FIELD}"), "Should NOT emit RootCompositeField for enum field")
    }

    @Test
    fun `root list composite field stays CompositeField`() {
        val sdl = """
            type Foo { name: String }
            type Query { foos: [Foo] }
        """.trimIndent()
        val result = genObject(sdl, "Query").toString()
        assertFalse(result.contains("${cfg.REFLECTED_ROOT_COMPOSITE_FIELD}"), "Should NOT emit RootCompositeField for list field")
        assertTrue(result.contains("${cfg.REFLECTED_COMPOSITE_FIELD}"), "Should use CompositeField for list field")
    }

    @Test
    fun `non-root composite field stays CompositeField`() {
        val sdl = """
            type Bar { name: String }
            type Foo { bar: Bar }
            type Query { dummy: Int }
        """.trimIndent()
        val result = genObject(sdl, "Foo").toString()
        assertFalse(result.contains("${cfg.REFLECTED_ROOT_COMPOSITE_FIELD}"), "Should NOT emit RootCompositeField for non-root type")
        assertTrue(result.contains("${cfg.REFLECTED_COMPOSITE_FIELD}"), "Should use CompositeField for non-root type")
    }

    @Test
    fun `scalar field on root stays Field`() {
        val result = genObject("type Query { x: Int }", "Query").toString()
        assertFalse(result.contains("${cfg.REFLECTED_ROOT_COMPOSITE_FIELD}"), "Should NOT emit RootCompositeField for scalar field")
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
        assertTrue(result.contains("Builder(context, engineObject.type, toBuilderEOD())"))
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
        assertTrue(result.contains("type: graphql.schema.GraphQLObjectType"))
        assertTrue(result.contains("baseEngineObjectData: EngineObjectData"))
    }
}
