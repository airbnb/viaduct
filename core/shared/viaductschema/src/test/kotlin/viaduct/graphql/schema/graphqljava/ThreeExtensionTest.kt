package viaduct.graphql.schema.graphqljava

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

/**
 * Verifies that three-extension types correctly place members and directives
 * in the right extensions for both GJSchema and GJSchemaRaw implementations.
 */
class ThreeExtensionTest {
    @Test
    fun `enum - three extensions with members and directives`() {
        val sdl = """
            directive @tag(name: String!) repeatable on ENUM

            enum Status @tag(name: "base") { BASE_VALUE }
            extend enum Status @tag(name: "ext1") { EXT1_VALUE }
            extend enum Status @tag(name: "ext2") { EXT2_VALUE }

            type Query { status: Status }
        """.trimIndent()

        verifyThreeExtensions<ViaductSchema.Enum>(sdl, "Status") { enumType ->
            val extensions = enumType.extensions.toList()
            assertEquals(3, extensions.size)

            // Extension 0: base
            assertTrue(extensions[0].isBase)
            assertEquals(listOf("BASE_VALUE"), extensions[0].members.map { it.name })
            assertEquals(listOf("\"base\""), extensions[0].appliedDirectives.map { it.arguments["name"].toString() })

            // Extension 1: ext1
            assertFalse(extensions[1].isBase)
            assertEquals(listOf("EXT1_VALUE"), extensions[1].members.map { it.name })
            assertEquals(listOf("\"ext1\""), extensions[1].appliedDirectives.map { it.arguments["name"].toString() })

            // Extension 2: ext2
            assertFalse(extensions[2].isBase)
            assertEquals(listOf("EXT2_VALUE"), extensions[2].members.map { it.name })
            assertEquals(listOf("\"ext2\""), extensions[2].appliedDirectives.map { it.arguments["name"].toString() })
        }
    }

    @Test
    fun `object - three extensions with fields and directives`() {
        val sdl = """
            directive @tag(name: String!) repeatable on OBJECT

            type User @tag(name: "base") { baseField: String }
            extend type User @tag(name: "ext1") { ext1Field: String }
            extend type User @tag(name: "ext2") { ext2Field: String }

            type Query { user: User }
        """.trimIndent()

        verifyThreeExtensions<ViaductSchema.Object>(sdl, "User") { objType ->
            val extensions = objType.extensions.toList()
            assertEquals(3, extensions.size)

            // Extension 0: base
            assertTrue(extensions[0].isBase)
            assertEquals(listOf("baseField"), extensions[0].members.map { it.name })
            assertEquals(listOf("\"base\""), extensions[0].appliedDirectives.map { it.arguments["name"].toString() })

            // Extension 1: ext1
            assertFalse(extensions[1].isBase)
            assertEquals(listOf("ext1Field"), extensions[1].members.map { it.name })
            assertEquals(listOf("\"ext1\""), extensions[1].appliedDirectives.map { it.arguments["name"].toString() })

            // Extension 2: ext2
            assertFalse(extensions[2].isBase)
            assertEquals(listOf("ext2Field"), extensions[2].members.map { it.name })
            assertEquals(listOf("\"ext2\""), extensions[2].appliedDirectives.map { it.arguments["name"].toString() })
        }
    }

    @Test
    fun `interface - three extensions with fields and directives`() {
        val sdl = """
            directive @tag(name: String!) repeatable on INTERFACE

            interface Node @tag(name: "base") { baseField: ID! }
            extend interface Node @tag(name: "ext1") { ext1Field: String }
            extend interface Node @tag(name: "ext2") { ext2Field: String }

            type User implements Node {
                baseField: ID!
                ext1Field: String
                ext2Field: String
            }

            type Query { node: Node }
        """.trimIndent()

        verifyThreeExtensions<ViaductSchema.Interface>(sdl, "Node") { ifaceType ->
            val extensions = ifaceType.extensions.toList()
            assertEquals(3, extensions.size)

            // Extension 0: base
            assertTrue(extensions[0].isBase)
            assertEquals(listOf("baseField"), extensions[0].members.map { it.name })
            assertEquals(listOf("\"base\""), extensions[0].appliedDirectives.map { it.arguments["name"].toString() })

            // Extension 1: ext1
            assertFalse(extensions[1].isBase)
            assertEquals(listOf("ext1Field"), extensions[1].members.map { it.name })
            assertEquals(listOf("\"ext1\""), extensions[1].appliedDirectives.map { it.arguments["name"].toString() })

            // Extension 2: ext2
            assertFalse(extensions[2].isBase)
            assertEquals(listOf("ext2Field"), extensions[2].members.map { it.name })
            assertEquals(listOf("\"ext2\""), extensions[2].appliedDirectives.map { it.arguments["name"].toString() })
        }
    }

    @Test
    fun `input - three extensions with fields and directives`() {
        val sdl = """
            directive @tag(name: String!) repeatable on INPUT_OBJECT

            input UserInput @tag(name: "base") { baseField: String }
            extend input UserInput @tag(name: "ext1") { ext1Field: String }
            extend input UserInput @tag(name: "ext2") { ext2Field: String }

            type Query { createUser(input: UserInput): String }
        """.trimIndent()

        verifyThreeExtensions<ViaductSchema.Input>(sdl, "UserInput") { inputType ->
            val extensions = inputType.extensions.toList()
            assertEquals(3, extensions.size)

            // Extension 0: base
            assertTrue(extensions[0].isBase)
            assertEquals(listOf("baseField"), extensions[0].members.map { it.name })
            assertEquals(listOf("\"base\""), extensions[0].appliedDirectives.map { it.arguments["name"].toString() })

            // Extension 1: ext1
            assertFalse(extensions[1].isBase)
            assertEquals(listOf("ext1Field"), extensions[1].members.map { it.name })
            assertEquals(listOf("\"ext1\""), extensions[1].appliedDirectives.map { it.arguments["name"].toString() })

            // Extension 2: ext2
            assertFalse(extensions[2].isBase)
            assertEquals(listOf("ext2Field"), extensions[2].members.map { it.name })
            assertEquals(listOf("\"ext2\""), extensions[2].appliedDirectives.map { it.arguments["name"].toString() })
        }
    }

    @Test
    fun `union - three extensions with members and directives`() {
        val sdl = """
            directive @tag(name: String!) repeatable on UNION

            type BaseType { base: String }
            type Ext1Type { ext1: String }
            type Ext2Type { ext2: String }

            union Result @tag(name: "base") = BaseType
            extend union Result @tag(name: "ext1") = Ext1Type
            extend union Result @tag(name: "ext2") = Ext2Type

            type Query { result: Result }
        """.trimIndent()

        verifyThreeExtensions<ViaductSchema.Union>(sdl, "Result") { unionType ->
            val extensions = unionType.extensions.toList()
            assertEquals(3, extensions.size)

            // Extension 0: base
            assertTrue(extensions[0].isBase)
            assertEquals(listOf("BaseType"), extensions[0].members.map { it.name })
            assertEquals(listOf("\"base\""), extensions[0].appliedDirectives.map { it.arguments["name"].toString() })

            // Extension 1: ext1
            assertFalse(extensions[1].isBase)
            assertEquals(listOf("Ext1Type"), extensions[1].members.map { it.name })
            assertEquals(listOf("\"ext1\""), extensions[1].appliedDirectives.map { it.arguments["name"].toString() })

            // Extension 2: ext2
            assertFalse(extensions[2].isBase)
            assertEquals(listOf("Ext2Type"), extensions[2].members.map { it.name })
            assertEquals(listOf("\"ext2\""), extensions[2].appliedDirectives.map { it.arguments["name"].toString() })
        }
    }

    private inline fun <reified T : ViaductSchema.TypeDef> verifyThreeExtensions(
        sdl: String,
        typeName: String,
        verifier: (T) -> Unit
    ) {
        val registry = SchemaParser().parse(sdl)

        // Test GJSchemaRaw (directly from registry)
        println("=== GJSchemaRaw: $typeName ===")
        val gjSchemaRaw = ViaductSchema.fromTypeDefinitionRegistry(registry)
        val rawType = gjSchemaRaw.types[typeName] as T
        verifier(rawType)

        // Test GJSchema (from built schema)
        println("=== GJSchema: $typeName ===")
        val graphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
        val gjSchema = ViaductSchema.fromGraphQLSchema(graphQLSchema)
        val builtType = gjSchema.types[typeName] as T
        verifier(builtType)
    }
}
