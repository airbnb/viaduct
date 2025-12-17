package viaduct.tenant.codegen.dsl

import kotlin.test.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import viaduct.codegen.st.STContents
import viaduct.graphql.schema.ViaductSchema
import org.junit.jupiter.api.Assertions.assertNotNull
import viaduct.tenant.codegen.kotlingen.bytecode.mkSchema

class NodeInterfaceDslGenTest {

    private fun generateInterfaceDsl(sdl: String, interfaceName: String): STContents? {
        val schema = mkSchema(sdl)
        val interfaceType = schema.types[interfaceName] as? ViaductSchema.Interface
            ?: return null
        val implementingTypes = schema.types.values
            .filterIsInstance<ViaductSchema.Object>()
            .filter { obj -> obj.supers.any { it.name == interfaceName } }
        return nodeInterfaceDslGen(TestPackages.DSL_PACKAGE, interfaceType, implementingTypes)
    }

    @Nested
    @DisplayName("Builder Class Generation")
    inner class BuilderClassTests {

        @Test
        fun `generates interface builder class`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                    name: String
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("class NodeDslBuilder internal constructor()"))
        }
    }

    @Nested
    @DisplayName("Common Field Generation")
    inner class CommonFieldTests {

        @Test
        fun `generates common scalar fields as properties`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                    name: String
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("val id: Unit"))
            assertTrue(result.contains("addField(\"id\")"))
        }

        @Test
        fun `excludes complex fields from common fields`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                    relatedNodes: [Node]
                }
                type User implements Node {
                    id: ID!
                    relatedNodes: [Node]
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            // Should have id as property
            assertTrue(result!!.contains("val id: Unit"))
            // Should NOT have relatedNodes as property (it's complex)
            assertFalse(result.contains("val relatedNodes: Unit"))
        }
    }

    @Nested
    @DisplayName("Fragment Method Generation")
    inner class FragmentMethodTests {

        @Test
        fun `generates fragment methods for implementing types`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                    name: String
                }
                type Post implements Node {
                    id: ID!
                    title: String
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("fun onUser(block: UserDslBuilder.() -> Unit)"))
            assertTrue(result.contains("fun onPost(block: PostDslBuilder.() -> Unit)"))
        }

        @Test
        fun `generates inline fragment syntax in fragment methods`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("... on User"))
        }

        @Test
        fun `generates nested builder invocation in fragment methods`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("val nestedBuilder = UserDslBuilder()"))
            assertTrue(result.contains("nestedBuilder.block()"))
        }
    }

    @Nested
    @DisplayName("Multiple Implementing Types")
    inner class MultipleImplementingTypesTests {

        @Test
        fun `generates fragment methods for all implementing types`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                    name: String
                }
                type Post implements Node {
                    id: ID!
                    title: String
                }
                type Comment implements Node {
                    id: ID!
                    text: String
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("fun onUser(block: UserDslBuilder.() -> Unit)"))
            assertTrue(result.contains("fun onPost(block: PostDslBuilder.() -> Unit)"))
            assertTrue(result.contains("fun onComment(block: CommentDslBuilder.() -> Unit)"))
        }

        @Test
        fun `generates all inline fragment syntaxes`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                }
                type Post implements Node {
                    id: ID!
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("... on User"))
            assertTrue(result.contains("... on Post"))
        }
    }

    @Nested
    @DisplayName("Multiple Common Fields")
    inner class MultipleCommonFieldsTests {

        @Test
        fun `generates multiple common scalar fields`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                    createdAt: String!
                    updatedAt: String
                }
                type User implements Node {
                    id: ID!
                    createdAt: String!
                    updatedAt: String
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("val id: Unit"))
            assertTrue(result.contains("val createdAt: Unit"))
            assertTrue(result.contains("val updatedAt: Unit"))
        }

        @Test
        fun `generates addField calls for all common fields`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                    name: String
                }
                type User implements Node {
                    id: ID!
                    name: String
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("addField(\"id\")"))
            assertTrue(result.contains("addField(\"name\")"))
        }
    }

    @Nested
    @DisplayName("Private Members")
    inner class PrivateMembersTests {

        @Test
        fun `generates private fields list`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("private val fields = mutableListOf<String>()"))
        }

        @Test
        fun `generates private addField method`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("private fun addField(name: String)"))
        }
    }

    @Nested
    @DisplayName("Build Method")
    inner class BuildMethodTests {

        @Test
        fun `generates internal build method`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("internal fun build(): String = fields.joinToString(\" \")"))
        }
    }

    @Nested
    @DisplayName("Fields with Arguments Exclusion")
    inner class FieldsWithArgsExclusionTests {

        @Test
        fun `excludes fields with arguments from common fields`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                    connection(first: Int): [Node]
                }
                type User implements Node {
                    id: ID!
                    connection(first: Int): [Node]
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            // id should be included (no args)
            assertTrue(result!!.contains("val id: Unit"))
            // connection should NOT be a property (has args)
            assertFalse(result.contains("val connection: Unit"))
        }
    }

    @Nested
    @DisplayName("Documentation")
    inner class DocumentationTests {

        @Test
        fun `generates class documentation with interface name`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("DSL builder for selecting fields from the `Node` GraphQL interface"))
        }
    }

    @Nested
    @DisplayName("Package Declaration")
    inner class PackageTests {

        @Test
        fun `generates correct package declaration`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("package ${TestPackages.DSL_PACKAGE}"))
        }
    }

    @Nested
    @DisplayName("File Suppress Annotation")
    inner class SuppressAnnotationTests {

        @Test
        fun `generates file-level suppress annotation`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("@file:Suppress(\"warnings\")"))
        }
    }

    @Nested
    @DisplayName("Enum Common Fields")
    inner class EnumCommonFieldsTests {

        @Test
        fun `includes enum fields in common fields`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                enum Status { ACTIVE INACTIVE }
                interface Node {
                    id: ID!
                    status: Status
                }
                type User implements Node {
                    id: ID!
                    status: Status
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("val status: Unit"))
        }
    }

    @Nested
    @DisplayName("Fragment Build Output")
    inner class FragmentBuildOutputTests {

        @Test
        fun `adds fragment with nested build output`() {
            val result = generateInterfaceDsl(
                """
                type Query { node(id: ID!): Node }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                }
                """.trimIndent(),
                "Node"
            )?.toString()

            assertNotNull(result, "Expected interface DSL to be generated")
            assertTrue(result!!.contains("\${nestedBuilder.build()}"))
        }
    }
}
