package viaduct.tenant.codegen.dsl

import org.junit.jupiter.api.Nested
import kotlin.test.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import viaduct.codegen.st.STContents
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.kotlingen.bytecode.mkSchema

class QueryDslGenTest {

    private fun generateQueryDsl(sdl: String): STContents {
        val schema = mkSchema(sdl)
        val baseTypeMapper = ViaductBaseTypeMapper(schema)
        val queryType = schema.types["Query"]!! as ViaductSchema.Object
        return queryDslGen(TestPackages.DSL_PACKAGE, queryType, baseTypeMapper)
    }

    @Nested
    @DisplayName("Query Function Generation")
    inner class QueryFunctionTests {

        @Test
        fun `generates query function with correct signature`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun query(name: String? = null, block: QueryDslBuilder.() -> Unit): String"))
        }

        @Test
        fun `generates query function that builds query string`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("return \"query"))
            assertTrue(result.contains("builder.build()"))
        }
    }

    @Nested
    @DisplayName("Builder Class Generation")
    inner class BuilderClassTests {

        @Test
        fun `generates QueryDslBuilder class with internal constructor`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("class QueryDslBuilder internal constructor()"))
        }

        @Test
        fun `generates private fields list`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("private val fields = mutableListOf<String>()"))
        }

        @Test
        fun `generates build method`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("internal fun build(): String = fields.joinToString(\" \")"))
        }
    }

    @Nested
    @DisplayName("Scalar Field Generation")
    inner class ScalarFieldTests {

        @Test
        fun `generates scalar fields as properties`() {
            val result = generateQueryDsl(
                """
                type Query {
                    greeting: String
                    count: Int
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("val greeting: Unit"))
            assertTrue(result.contains("val count: Unit"))
        }

        @Test
        fun `generates addField calls for scalar fields`() {
            val result = generateQueryDsl(
                """
                type Query {
                    greeting: String
                    count: Int
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("addField(\"greeting\")"))
            assertTrue(result.contains("addField(\"count\")"))
        }

        @Test
        fun `generates enum fields as properties`() {
            val result = generateQueryDsl(
                """
                type Query {
                    status: Status
                }
                enum Status {
                    ACTIVE
                    INACTIVE
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("val status: Unit"))
        }
    }

    @Nested
    @DisplayName("Complex Field Generation")
    inner class ComplexFieldTests {

        @Test
        fun `generates object fields as functions with builder block`() {
            val result = generateQueryDsl(
                """
                type Query {
                    user: User
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun user("))
            assertTrue(result.contains("block: UserDslBuilder.() -> Unit"))
        }

        @Test
        fun `generates fields with arguments as functions`() {
            val result = generateQueryDsl(
                """
                type Query {
                    user(id: ID!): User
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun user("))
            assertTrue(result.contains("id:"))
        }

        @Test
        fun `generates input type arguments with specialized builder`() {
            val result = generateQueryDsl(
                """
                type Query {
                    users(filter: UserFilter): [User]
                }
                input UserFilter {
                    name: String
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            // Fields with input args use specialized query builders
            assertTrue(result.contains("fun users("))
            assertTrue(result.contains("alias: String? = null"))
            assertTrue(result.contains("UsersQueryBuilder"))
            assertTrue(result.contains("nestedBuilder.buildArgs()"))
        }

        @Test
        fun `generates interface fields with builder block`() {
            val result = generateQueryDsl(
                """
                type Query {
                    node(id: ID!): Node
                }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun node("))
            assertTrue(result.contains("block: NodeDslBuilder.() -> Unit"))
        }

        @Test
        fun `generates alias parameter for complex fields`() {
            val result = generateQueryDsl(
                """
                type Query {
                    user: User
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("alias: String? = null"))
        }
    }

    @Nested
    @DisplayName("Serialization Methods")
    inner class SerializationTests {

        @Test
        fun `generates serializeValue method`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("private fun serializeValue(value: Any?): String"))
        }

        @Test
        fun `handles Map serialization in serializeValue`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("is Map<*, *>"))
        }

        @Test
        fun `handles null serialization`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("null -> \"null\""))
        }

        @Test
        fun `handles String serialization with escaping`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("is String"))
            assertTrue(result.contains("replace"))
        }

        @Test
        fun `handles Boolean serialization`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("is Boolean"))
        }

        @Test
        fun `handles Number serialization`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("is Number"))
        }

        @Test
        fun `handles Enum serialization`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("is Enum<*>"))
            assertTrue(result.contains(".name"))
        }

        @Test
        fun `handles List serialization`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("is List<*>"))
        }
    }

    @Nested
    @DisplayName("List Type Fields")
    inner class ListTypeFieldTests {

        @Test
        fun `generates list scalar fields as properties`() {
            val result = generateQueryDsl(
                """
                type Query {
                    names: [String]
                    ids: [ID]
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("val names: Unit"))
            assertTrue(result.contains("val ids: Unit"))
        }

        @Test
        fun `generates list object fields as functions`() {
            val result = generateQueryDsl(
                """
                type Query {
                    users: [User]
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun users("))
            assertTrue(result.contains("block: UserDslBuilder.() -> Unit"))
        }

        @Test
        fun `generates non-null list fields correctly`() {
            val result = generateQueryDsl(
                """
                type Query {
                    users: [User!]!
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun users("))
        }
    }

    @Nested
    @DisplayName("Multiple Arguments")
    inner class MultipleArgumentsTests {

        @Test
        fun `generates function with multiple scalar arguments`() {
            val result = generateQueryDsl(
                """
                type Query {
                    search(query: String!, limit: Int, offset: Int): [Result]
                }
                type Result {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun search("))
            assertTrue(result.contains("query:"))
            assertTrue(result.contains("limit:"))
            assertTrue(result.contains("offset:"))
        }

        @Test
        fun `generates function with mixed scalar and input arguments`() {
            val result = generateQueryDsl(
                """
                type Query {
                    search(filter: SearchFilter, limit: Int): [Result]
                }
                input SearchFilter {
                    term: String
                }
                type Result {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun search("))
            // When there's an input arg, it uses specialized builder
            assertTrue(result.contains("SearchQueryBuilder"))
        }
    }

    @Nested
    @DisplayName("Union Type Fields")
    inner class UnionTypeFieldTests {

        @Test
        fun `generates union fields with builder block`() {
            val result = generateQueryDsl(
                """
                type Query {
                    searchResult: SearchResult
                }
                union SearchResult = User | Post
                type User {
                    id: ID
                    name: String
                }
                type Post {
                    id: ID
                    title: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun searchResult("))
            assertTrue(result.contains("block: SearchResultDslBuilder.() -> Unit"))
        }
    }

    @Nested
    @DisplayName("Nullable vs Non-Nullable Arguments")
    inner class NullabilityTests {

        @Test
        fun `generates nullable argument types`() {
            val result = generateQueryDsl(
                """
                type Query {
                    user(id: ID): User
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun user("))
            // Nullable ID should have ? in the type
            assertTrue(result.contains("id: String?"))
        }

        @Test
        fun `generates non-nullable argument types`() {
            val result = generateQueryDsl(
                """
                type Query {
                    user(id: ID!): User
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("fun user("))
            assertTrue(result.contains("id: String,") || result.contains("id: String)"))
        }
    }

    @Nested
    @DisplayName("Alias Support")
    inner class AliasTests {

        @Test
        fun `generates aliasPrefix in complex field builder`() {
            val result = generateQueryDsl(
                """
                type Query {
                    user: User
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("val aliasPrefix = if (alias != null) alias + \": \" else \"\""))
        }

        @Test
        fun `uses aliasPrefix when building field string`() {
            val result = generateQueryDsl(
                """
                type Query {
                    user: User
                }
                type User {
                    id: ID
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("addField(aliasPrefix +"))
        }
    }

    @Nested
    @DisplayName("SerializeArgsMap Generation")
    inner class SerializeArgsMapTests {

        @Test
        fun `generates serializeArgsMap method`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("private fun serializeArgsMap(args: Map<String, Any?>): String"))
        }

        @Test
        fun `serializeArgsMap joins entries with comma`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("joinToString(\", \")"))
        }
    }

    @Nested
    @DisplayName("Package Declaration")
    inner class PackageTests {

        @Test
        fun `generates correct package declaration`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("package ${TestPackages.DSL_PACKAGE}"))
        }
    }

    @Nested
    @DisplayName("File Suppress Annotation")
    inner class SuppressAnnotationTests {

        @Test
        fun `generates file-level suppress annotation`() {
            val result = generateQueryDsl(
                """
                type Query {
                    hello: String
                }
                """.trimIndent()
            ).toString()

            assertTrue(result.contains("@file:Suppress(\"warnings\")"))
        }
    }
}
