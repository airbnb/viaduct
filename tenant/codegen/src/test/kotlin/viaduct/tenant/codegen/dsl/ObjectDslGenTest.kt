package viaduct.tenant.codegen.dsl

import kotlin.test.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import viaduct.codegen.st.STContents
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.kotlingen.bytecode.mkSchema

class ObjectDslGenTest {

    private fun generateObjectDsl(sdl: String, typeName: String): STContents {
        val schema = mkSchema(sdl)
        val baseTypeMapper = ViaductBaseTypeMapper(schema)
        val objectType = schema.types[typeName]!! as ViaductSchema.Object
        return objectDslGen(TestPackages.DSL_PACKAGE, objectType, baseTypeMapper)
    }

    @Nested
    @DisplayName("Builder Class Generation")
    inner class BuilderClassTests {

        @Test
        fun `generates named builder class`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("class UserDslBuilder internal constructor()"))
        }

        @Test
        fun `generates private fields list and addField method`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("protected val fields = mutableListOf<String>()"))
            assertTrue(result.contains("protected fun addField(name: String)"))
        }
    }

    @Nested
    @DisplayName("Scalar Field Generation")
    inner class ScalarFieldTests {

        @Test
        fun `generates scalar fields as properties`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("val id: Unit"))
            assertTrue(result.contains("val name: Unit"))
        }

        @Test
        fun `generates addField calls in property getters`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("addField(\"id\")"))
            assertTrue(result.contains("addField(\"name\")"))
        }
    }

    @Nested
    @DisplayName("Complex Field Generation")
    inner class ComplexFieldTests {

        @Test
        fun `generates complex fields as functions with builder block`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                    address: Address
                }
                type Address {
                    street: String
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("fun address(block: AddressDslBuilder.() -> Unit)"))
        }

        @Test
        fun `generates fields with arguments as functions`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                    posts(limit: Int): [Post]
                }
                type Post {
                    id: ID
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("fun posts("))
            // Int? is nullable in GraphQL, check for limit parameter
            assertTrue(result.contains("limit:"))
        }

        @Test
        fun `generates argument serialization for fields with args`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    posts(limit: Int, offset: Int): [Post]
                }
                type Post {
                    id: ID
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("serializeValue(limit)"))
            assertTrue(result.contains("serializeValue(offset)"))
        }

        @Test
        fun `generates interface field with builder block`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                interface Node {
                    id: ID!
                }
                type User {
                    id: ID
                    relatedNode: Node
                }
                type Post implements Node {
                    id: ID!
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("fun relatedNode("))
            assertTrue(result.contains("block: NodeDslBuilder.() -> Unit"))
        }

        @Test
        fun `generates union field with builder block`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                union SearchResult = User | Post
                type User {
                    id: ID
                    searchResult: SearchResult
                }
                type Post {
                    id: ID
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("fun searchResult("))
            assertTrue(result.contains("block: SearchResultDslBuilder.() -> Unit"))
        }
    }

    @Nested
    @DisplayName("Open Class Generation")
    inner class OpenClassTests {

        @Test
        fun `generates open class`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("open class UserDslBuilder"))
        }

        @Test
        fun `generates open build method`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("internal open fun build(): String"))
        }
    }

    @Nested
    @DisplayName("Protected Members")
    inner class ProtectedMembersTests {

        @Test
        fun `generates protected fields list`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("protected val fields = mutableListOf<String>()"))
        }

        @Test
        fun `generates protected addField method`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("protected fun addField(name: String)"))
        }
    }

    @Nested
    @DisplayName("Serialization Methods")
    inner class SerializationTests {

        @Test
        fun `generates serializeValue method`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("private fun serializeValue(value: Any?): String"))
        }

        @Test
        fun `handles all value types`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("is String"))
            assertTrue(result.contains("is Boolean"))
            assertTrue(result.contains("is Number"))
            assertTrue(result.contains("is Enum<*>"))
            assertTrue(result.contains("is Map<*, *>"))
            assertTrue(result.contains("is List<*>"))
        }
    }

    @Nested
    @DisplayName("List Type Fields")
    inner class ListTypeFieldTests {

        @Test
        fun `generates list scalar fields as properties`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    tags: [String]
                    scores: [Int]
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("val tags: Unit"))
            assertTrue(result.contains("val scores: Unit"))
        }

        @Test
        fun `generates list object fields as functions`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                    friends: [User]
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("fun friends("))
            assertTrue(result.contains("block: UserDslBuilder.() -> Unit"))
        }
    }

    @Nested
    @DisplayName("Enum Fields")
    inner class EnumFieldTests {

        @Test
        fun `generates enum fields as properties`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                enum Status { ACTIVE INACTIVE }
                type User {
                    id: ID
                    status: Status
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("val status: Unit"))
            assertTrue(result.contains("addField(\"status\")"))
        }

        @Test
        fun `generates list of enums as property`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                enum Role { ADMIN USER GUEST }
                type User {
                    id: ID
                    roles: [Role]
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("val roles: Unit"))
        }
    }

    @Nested
    @DisplayName("All GraphQL Scalar Types")
    inner class ScalarTypesTests {

        @Test
        fun `generates ID field as property`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("val id: Unit"))
        }

        @Test
        fun `generates String field as property`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    name: String
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("val name: Unit"))
        }

        @Test
        fun `generates Int field as property`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    age: Int
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("val age: Unit"))
        }

        @Test
        fun `generates Float field as property`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    score: Float
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("val score: Unit"))
        }

        @Test
        fun `generates Boolean field as property`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    active: Boolean
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("val active: Unit"))
        }
    }

    @Nested
    @DisplayName("Documentation")
    inner class DocumentationTests {

        @Test
        fun `generates class documentation with type name`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("DSL builder for selecting fields from the `User` GraphQL type"))
        }
    }

    @Nested
    @DisplayName("Package Declaration")
    inner class PackageTests {

        @Test
        fun `generates correct package declaration`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("package ${TestPackages.DSL_PACKAGE}"))
        }
    }

    @Nested
    @DisplayName("Nested Object Selection")
    inner class NestedObjectTests {

        @Test
        fun `generates nested selection with build call`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                    address: Address
                }
                type Address {
                    street: String
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("nestedBuilder.build()"))
        }

        @Test
        fun `formats nested selection with braces`() {
            val result = generateObjectDsl(
                """
                type Query { user: User }
                type User {
                    id: ID
                    address: Address
                }
                type Address {
                    street: String
                }
                """.trimIndent(),
                "User"
            ).toString()

            assertTrue(result.contains("{ \${nestedBuilder.build()} }"))
        }
    }
}
