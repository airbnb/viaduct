package viaduct.tenant.codegen.dsl

import kotlin.test.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import viaduct.codegen.st.STContents
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.kotlingen.bytecode.mkSchema

class MutationDslGenTest {

    private fun generateMutationDsl(sdl: String): STContents {
        val schema = mkSchema(sdl)
        val baseTypeMapper = ViaductBaseTypeMapper(schema)
        val mutationType = schema.types["Mutation"]!! as ViaductSchema.Object
        return mutationDslGen(TestPackages.DSL_PACKAGE, mutationType, baseTypeMapper)
    }

    @Nested
    @DisplayName("Mutation Function Generation")
    inner class MutationFunctionTests {

        @Test
        fun `generates mutation function with correct signature`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    updateUser: Boolean
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun mutation(name: String? = null, block: MutationDslBuilder.() -> Unit): String"))
        }
    }

    @Nested
    @DisplayName("Builder Class Generation")
    inner class BuilderClassTests {

        @Test
        fun `generates MutationDslBuilder class with internal constructor`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    updateUser: Boolean
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("class MutationDslBuilder internal constructor()"))
        }
    }

    @Nested
    @DisplayName("Scalar Field Generation")
    inner class ScalarFieldTests {

        @Test
        fun `generates scalar mutation fields as functions not properties`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    deleteUser(id: ID!): Boolean
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun deleteUser("))
            // Should NOT be a property
            assertFalse(result.contains("val deleteUser: Unit"))
        }

        @Test
        fun `generates scalar fields without args as functions`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    ping: Boolean
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun ping("))
        }
    }

    @Nested
    @DisplayName("Complex Field Generation")
    inner class ComplexFieldTests {

        @Test
        fun `generates complex mutation fields with builder block`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    createUser(name: String!): User
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun createUser("))
            assertTrue(result.contains("block: UserDslBuilder.() -> Unit"))
        }

        @Test
        fun `generates nested builder invocation`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    createUser(name: String!): User
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("val nestedBuilder = UserDslBuilder()"))
            assertTrue(result.contains("nestedBuilder.block()"))
            assertTrue(result.contains("nestedBuilder.build()"))
        }
    }

    @Nested
    @DisplayName("Input Type Arguments")
    inner class InputTypeArgumentTests {

        @Test
        fun `generates specialized mutation builder for input args`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    createUser(input: CreateUserInput!): User
                }
                input CreateUserInput {
                    name: String!
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("CreateUserMutationBuilder"))
        }

        @Test
        fun `uses buildArgs for input arguments`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    createUser(input: CreateUserInput!): User
                }
                input CreateUserInput {
                    name: String!
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("nestedBuilder.buildArgs()"))
        }

        @Test
        fun `generates serializeArgsMap call for input args`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    createUser(input: CreateUserInput!): User
                }
                input CreateUserInput {
                    name: String!
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("serializeArgsMap(argsMap)"))
        }
    }

    @Nested
    @DisplayName("Serialization Methods")
    inner class SerializationTests {

        @Test
        fun `generates serializeValue method`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    ping: Boolean
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("private fun serializeValue(value: Any?): String"))
        }

        @Test
        fun `generates serializeArgsMap method`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    ping: Boolean
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("private fun serializeArgsMap(args: Map<String, Any?>): String"))
        }

        @Test
        fun `handles all value types in serializeValue`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    ping: Boolean
                }
                """.trimIndent()
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
    @DisplayName("Alias Support")
    inner class AliasTests {

        @Test
        fun `generates alias parameter for scalar mutations`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    ping: Boolean
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("alias: String? = null"))
        }

        @Test
        fun `generates alias parameter for complex mutations`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    createUser(name: String!): User
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("alias: String? = null"))
        }

        @Test
        fun `generates aliasPrefix logic`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    ping: Boolean
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("val aliasPrefix = if (alias != null) alias + \": \" else \"\""))
        }
    }

    @Nested
    @DisplayName("Interface Return Types")
    inner class InterfaceReturnTypeTests {

        @Test
        fun `generates interface builder for interface return type`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    createNode(type: String!): Node
                }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("block: NodeDslBuilder.() -> Unit"))
        }
    }

    @Nested
    @DisplayName("Union Return Types")
    inner class UnionReturnTypeTests {

        @Test
        fun `generates union builder for union return type`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    createContent(type: String!): Content
                }
                union Content = Post | Comment
                type Post {
                    id: ID
                    title: String
                }
                type Comment {
                    id: ID
                    text: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("block: ContentDslBuilder.() -> Unit"))
        }
    }

    @Nested
    @DisplayName("Multiple Arguments")
    inner class MultipleArgumentsTests {

        @Test
        fun `generates multiple scalar arguments`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    updateUser(id: ID!, name: String, email: String): Boolean
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("id:"))
            assertTrue(result.contains("name:"))
            assertTrue(result.contains("email:"))
        }

        @Test
        fun `serializes multiple arguments`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    updateUser(id: ID!, name: String): Boolean
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("serializeValue(id)"))
            assertTrue(result.contains("serializeValue(name)"))
        }
    }

    @Nested
    @DisplayName("List Return Types")
    inner class ListReturnTypeTests {

        @Test
        fun `generates builder for list of objects`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    createUsers(names: [String!]!): [User]
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("block: UserDslBuilder.() -> Unit"))
        }
    }

    @Nested
    @DisplayName("Enum Arguments")
    inner class EnumArgumentsTests {

        @Test
        fun `generates enum argument type`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    setStatus(status: Status!): Boolean
                }
                enum Status { ACTIVE INACTIVE }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("status:"))
        }
    }

    @Nested
    @DisplayName("Package Declaration")
    inner class PackageTests {

        @Test
        fun `generates correct package declaration`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    ping: Boolean
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("package ${TestPackages.DSL_PACKAGE}"))
        }
    }

    @Nested
    @DisplayName("Documentation")
    inner class DocumentationTests {

        @Test
        fun `generates mutation function documentation`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    ping: Boolean
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("Creates a GraphQL mutation string using a type-safe DSL"))
        }

        @Test
        fun `generates builder class documentation`() {
            val result = generateMutationDsl(
                """
                type Query { empty: Int }
                type Mutation {
                    ping: Boolean
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("DSL builder for constructing GraphQL mutations"))
        }
    }
}
