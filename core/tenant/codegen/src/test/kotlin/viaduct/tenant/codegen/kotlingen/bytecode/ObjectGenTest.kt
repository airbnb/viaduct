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
        assertTrue(result.contains("${cfg.REFLECTED_ROOT_OBJECT_FIELD}"), "Should emit RootObjectField for root composite field")
        assertTrue(result.contains("${cfg.REFLECTED_ROOT_OBJECT_FIELD_IMPL}"), "Should use RootObjectFieldImpl")
        assertTrue(result.contains("pkg.Query_Foo_Arguments"), "Should include Arguments type")
        assertTrue(result.contains("""listOf("foo")"""), "Should include rootFieldPath with single element")
    }

    @Test
    fun `root composite field with no args uses NoArguments`() {
        val sdl = """
            type Foo { name: String }
            type Query { foo: Foo }
        """.trimIndent()
        val result = genObject(sdl, "Query").toString()
        assertTrue(result.contains("${cfg.REFLECTED_ROOT_OBJECT_FIELD}"), "Should emit RootObjectField for zero-arg root composite field")
        assertTrue(result.contains(cfg.ARGUMENTS_NO_ARGUMENTS.toString().replace('$', '.')), "Should use Arguments.NoArguments for zero-arg field")
        assertTrue(result.contains("""listOf("foo")"""), "Should include rootFieldPath")
    }

    @Test
    fun `root composite field on namespace type has correct path`() {
        val sdl = """
            directive @namespaceType on OBJECT
            type Foo { name: String }
            type Factories @namespaceType { create: Foo }
            type Query { _factories: Factories }
        """.trimIndent()
        val result = genObject(sdl, "Factories").toString()
        assertTrue(result.contains("${cfg.REFLECTED_ROOT_OBJECT_FIELD}"), "Should emit RootObjectField on namespace type")
        assertTrue(result.contains("""listOf("_factories", "create")"""), "Should include full path from Query through namespace")
    }

    @Test
    fun `root composite field on deeply nested namespace type has correct path`() {
        val sdl = """
            directive @namespaceType on OBJECT
            type Product { name: String }
            type ProductFactory @namespaceType { create: Product }
            type Factories @namespaceType { products: ProductFactory }
            type Query { _factories: Factories }
        """.trimIndent()
        val result = genObject(sdl, "ProductFactory").toString()
        assertTrue(result.contains("""listOf("_factories", "products", "create")"""), "Should include full path through two namespace levels")
    }

    @Test
    fun `root enum field stays CompositeField not RootObjectField`() {
        val sdl = """
            enum Status { ACTIVE INACTIVE }
            type Query { status: Status }
        """.trimIndent()
        val result = genObject(sdl, "Query").toString()
        assertFalse(result.contains("${cfg.REFLECTED_ROOT_OBJECT_FIELD}"), "Should NOT emit RootObjectField for enum field")
    }

    @Test
    fun `root list composite field stays CompositeField`() {
        val sdl = """
            type Foo { name: String }
            type Query { foos: [Foo] }
        """.trimIndent()
        val result = genObject(sdl, "Query").toString()
        assertFalse(result.contains("${cfg.REFLECTED_ROOT_OBJECT_FIELD}"), "Should NOT emit RootObjectField for list field")
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
        assertFalse(result.contains("${cfg.REFLECTED_ROOT_OBJECT_FIELD}"), "Should NOT emit RootObjectField for non-root type")
        assertTrue(result.contains("${cfg.REFLECTED_COMPOSITE_FIELD}"), "Should use CompositeField for non-root type")
    }

    @Test
    fun `scalar field on root stays Field`() {
        val result = genObject("type Query { x: Int }", "Query").toString()
        assertFalse(result.contains("${cfg.REFLECTED_ROOT_OBJECT_FIELD}"), "Should NOT emit RootObjectField for scalar field")
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
        assertTrue(result.contains("Builder(getBuilderContext(), getBuilderEngineObject().type, toBuilderEOD())"))
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
        assertTrue(result.contains("baseEngineObjectData: EngineObjectData.Sync"))
    }
}
