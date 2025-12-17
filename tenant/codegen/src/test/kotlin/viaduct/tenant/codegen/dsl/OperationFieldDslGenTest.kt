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

class OperationFieldDslGenTest {

    private fun generateOperationFieldDsl(
        sdl: String,
        fieldName: String,
        operationType: OperationType
    ): STContents {
        val schema = mkSchema(sdl)
        val baseTypeMapper = ViaductBaseTypeMapper(schema)
        val operationTypeName = if (operationType == OperationType.QUERY) "Query" else "Mutation"
        val operationDef = schema.types[operationTypeName]!! as ViaductSchema.Object
        val field = operationDef.fields.first { it.name == fieldName }
        val returnType = field.type.baseTypeDef
        return operationFieldDslGen(TestPackages.DSL_PACKAGE, field, returnType, operationType, baseTypeMapper)
    }

    @Nested
    @DisplayName("Query Field Builder Generation")
    inner class QueryFieldBuilderTests {

        @Test
        fun `generates query field builder class`() {
            val result = generateOperationFieldDsl(
                """
                type Query {
                    searchUsers(filter: UserFilter!): [User]
                }
                input UserFilter {
                    name: String
                }
                type User {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "searchUsers",
                OperationType.QUERY
            ).toString()

            assertTrue(result.contains("class SearchUsersQueryBuilder internal constructor()"))
        }

        @Test
        fun `extends parent builder class`() {
            val result = generateOperationFieldDsl(
                """
                type Query {
                    searchUsers(filter: UserFilter!): [User]
                }
                input UserFilter {
                    name: String
                }
                type User {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "searchUsers",
                OperationType.QUERY
            ).toString()

            assertTrue(result.contains(": UserDslBuilder()"))
        }

        @Test
        fun `generates private argValues map`() {
            val result = generateOperationFieldDsl(
                """
                type Query {
                    searchUsers(filter: UserFilter!): [User]
                }
                input UserFilter {
                    name: String
                }
                type User {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "searchUsers",
                OperationType.QUERY
            ).toString()

            assertTrue(result.contains("private val argValues = mutableMapOf<String, Any?>()"))
        }

        @Test
        fun `generates buildArgs method`() {
            val result = generateOperationFieldDsl(
                """
                type Query {
                    searchUsers(filter: UserFilter!): [User]
                }
                input UserFilter {
                    name: String
                }
                type User {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "searchUsers",
                OperationType.QUERY
            ).toString()

            assertTrue(result.contains("internal fun buildArgs(): Map<String, Any?> = argValues.toMap()"))
        }
    }

    @Nested
    @DisplayName("Mutation Field Builder Generation")
    inner class MutationFieldBuilderTests {

        @Test
        fun `generates mutation field builder class`() {
            val result = generateOperationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createUser(input: CreateUserInput!): User
                }
                input CreateUserInput {
                    name: String!
                }
                type User {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "createUser",
                OperationType.MUTATION
            ).toString()

            assertTrue(result.contains("class CreateUserMutationBuilder internal constructor()"))
        }

        @Test
        fun `mutation builder extends parent type builder`() {
            val result = generateOperationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createUser(input: CreateUserInput!): User
                }
                input CreateUserInput {
                    name: String!
                }
                type User {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "createUser",
                OperationType.MUTATION
            ).toString()

            assertTrue(result.contains(": UserDslBuilder()"))
        }
    }

    @Nested
    @DisplayName("Input Argument Generation")
    inner class InputArgumentTests {

        @Test
        fun `generates input argument as DSL function`() {
            val result = generateOperationFieldDsl(
                """
                type Query {
                    searchUsers(filter: UserFilter!): [User]
                }
                input UserFilter {
                    name: String
                }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "searchUsers",
                OperationType.QUERY
            ).toString()

            assertTrue(result.contains("fun filter(block: UserFilterBuilder.() -> Unit)"))
        }

        @Test
        fun `generates input builder invocation`() {
            val result = generateOperationFieldDsl(
                """
                type Query {
                    searchUsers(filter: UserFilter!): [User]
                }
                input UserFilter {
                    name: String
                }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "searchUsers",
                OperationType.QUERY
            ).toString()

            assertTrue(result.contains("val inputBuilder = UserFilterBuilder()"))
            assertTrue(result.contains("inputBuilder.block()"))
            assertTrue(result.contains("argValues[\"filter\"] = inputBuilder.build()"))
        }

        @Test
        fun `handles multiple input arguments`() {
            val result = generateOperationFieldDsl(
                """
                type Query {
                    searchUsers(filter: UserFilter!, pagination: PaginationInput): [User]
                }
                input UserFilter {
                    name: String
                }
                input PaginationInput {
                    limit: Int
                    offset: Int
                }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "searchUsers",
                OperationType.QUERY
            ).toString()

            assertTrue(result.contains("fun filter(block: UserFilterBuilder.() -> Unit)"))
            assertTrue(result.contains("fun pagination(block: PaginationInputBuilder.() -> Unit)"))
        }
    }

    @Nested
    @DisplayName("Scalar Argument Generation")
    inner class ScalarArgumentTests {

        @Test
        fun `generates scalar argument as property`() {
            val result = generateOperationFieldDsl(
                """
                type Query {
                    searchUsers(filter: UserFilter!, limit: Int): [User]
                }
                input UserFilter {
                    name: String
                }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "searchUsers",
                OperationType.QUERY
            ).toString()

            assertTrue(result.contains("var limit: Int?"))
        }

        @Test
        fun `generates non-nullable scalar argument`() {
            val result = generateOperationFieldDsl(
                """
                type Query {
                    searchUsers(filter: UserFilter!, limit: Int!): [User]
                }
                input UserFilter {
                    name: String
                }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "searchUsers",
                OperationType.QUERY
            ).toString()

            assertTrue(result.contains("var limit: Int"))
            // Should not be nullable
            assertFalse(result.contains("var limit: Int?"))
        }

        @Test
        fun `generates String scalar argument`() {
            val result = generateOperationFieldDsl(
                """
                type Query {
                    searchUsers(filter: UserFilter!, query: String): [User]
                }
                input UserFilter {
                    name: String
                }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "searchUsers",
                OperationType.QUERY
            ).toString()

            assertTrue(result.contains("var query: String?"))
        }

        @Test
        fun `generates enum argument`() {
            val result = generateOperationFieldDsl(
                """
                type Query {
                    searchUsers(filter: UserFilter!, status: UserStatus): [User]
                }
                input UserFilter {
                    name: String
                }
                enum UserStatus { ACTIVE INACTIVE }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "searchUsers",
                OperationType.QUERY
            ).toString()

            assertTrue(result.contains("var status:"))
        }
    }

    @Nested
    @DisplayName("Mixed Arguments Generation")
    inner class MixedArgumentsTests {

        @Test
        fun `generates both input and scalar arguments`() {
            val result = generateOperationFieldDsl(
                """
                type Query {
                    searchUsers(filter: UserFilter!, limit: Int, offset: Int, sortBy: String): [User]
                }
                input UserFilter {
                    name: String
                }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "searchUsers",
                OperationType.QUERY
            ).toString()

            // Input argument
            assertTrue(result.contains("fun filter(block: UserFilterBuilder.() -> Unit)"))
            // Scalar arguments
            assertTrue(result.contains("var limit: Int?"))
            assertTrue(result.contains("var offset: Int?"))
            assertTrue(result.contains("var sortBy: String?"))
        }
    }

    @Nested
    @DisplayName("Documentation Generation")
    inner class DocumentationTests {

        @Test
        fun `generates class documentation`() {
            val result = generateOperationFieldDsl(
                """
                type Query {
                    searchUsers(filter: UserFilter!): [User]
                }
                input UserFilter {
                    name: String
                }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "searchUsers",
                OperationType.QUERY
            ).toString()

            assertTrue(result.contains("DSL builder for the `searchUsers` query field"))
        }

        @Test
        fun `generates mutation field documentation`() {
            val result = generateOperationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createUser(input: CreateUserInput!): User
                }
                input CreateUserInput {
                    name: String!
                }
                type User {
                    id: ID
                }
                """.trimIndent(),
                "createUser",
                OperationType.MUTATION
            ).toString()

            assertTrue(result.contains("DSL builder for the `createUser` mutation field"))
        }
    }

    @Nested
    @DisplayName("Builder Name Generation")
    inner class BuilderNameTests {

        @Test
        fun `getOperationFieldBuilderName generates correct query builder name`() {
            val name = getOperationFieldBuilderName("searchUsers", OperationType.QUERY)
            assertTrue(name == "SearchUsersQueryBuilder")
        }

        @Test
        fun `getOperationFieldBuilderName generates correct mutation builder name`() {
            val name = getOperationFieldBuilderName("createUser", OperationType.MUTATION)
            assertTrue(name == "CreateUserMutationBuilder")
        }

        @Test
        fun `getOperationFieldBuilderName capitalizes first letter`() {
            val name = getOperationFieldBuilderName("users", OperationType.QUERY)
            assertTrue(name == "UsersQueryBuilder")
        }
    }
}
