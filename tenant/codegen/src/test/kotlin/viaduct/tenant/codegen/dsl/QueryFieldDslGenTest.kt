package viaduct.tenant.codegen.dsl

import kotlin.test.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import viaduct.codegen.st.STContents
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.kotlingen.bytecode.mkSchema

class QueryFieldDslGenTest {

    private fun generateQueryFieldDsl(sdl: String, fieldName: String): STContents {
        val schema = mkSchema(sdl)
        val baseTypeMapper = ViaductBaseTypeMapper(schema)
        val queryDef = schema.types["Query"]!! as ViaductSchema.Object
        val field = queryDef.fields.first { it.name == fieldName }
        val returnType = field.type.baseTypeDef
        return queryFieldDslGen(TestPackages.DSL_PACKAGE, field, returnType, baseTypeMapper)
    }

    @Nested
    @DisplayName("Query Field Builder Class")
    inner class BuilderClassTests {

        @Test
        fun `generates query builder with correct name`() {
            val result = generateQueryFieldDsl(
                """
                type Query {
                    searchCharacter(search: CharacterSearchInput!): [Character]
                }
                input CharacterSearchInput {
                    name: String
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "searchCharacter"
            ).toString()

            assertTrue(result.contains("class SearchCharacterQueryBuilder"))
        }

        @Test
        fun `extends return type builder`() {
            val result = generateQueryFieldDsl(
                """
                type Query {
                    searchCharacter(search: CharacterSearchInput!): [Character]
                }
                input CharacterSearchInput {
                    name: String
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "searchCharacter"
            ).toString()

            assertTrue(result.contains(": CharacterDslBuilder()"))
        }
    }

    @Nested
    @DisplayName("Input Arguments as DSL Functions")
    inner class InputArgDslTests {

        @Test
        fun `generates DSL function for input argument`() {
            val result = generateQueryFieldDsl(
                """
                type Query {
                    searchCharacter(search: CharacterSearchInput!): [Character]
                }
                input CharacterSearchInput {
                    name: String
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "searchCharacter"
            ).toString()

            assertTrue(result.contains("fun search(block: CharacterSearchInputBuilder.() -> Unit)"))
        }

        @Test
        fun `stores input in argValues map`() {
            val result = generateQueryFieldDsl(
                """
                type Query {
                    searchCharacter(search: CharacterSearchInput!): [Character]
                }
                input CharacterSearchInput {
                    name: String
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "searchCharacter"
            ).toString()

            assertTrue(result.contains("argValues[\"search\"] = inputBuilder.build()"))
        }
    }

    @Nested
    @DisplayName("getQueryFieldBuilderName Helper")
    inner class BuilderNameHelperTests {

        @Test
        fun `generates correct builder name for simple field`() {
            val name = getQueryFieldBuilderName("users")
            assertEquals("UsersQueryBuilder", name)
        }

        @Test
        fun `generates correct builder name for camelCase field`() {
            val name = getQueryFieldBuilderName("searchCharacter")
            assertEquals("SearchCharacterQueryBuilder", name)
        }

        @Test
        fun `capitalizes first letter`() {
            val name = getQueryFieldBuilderName("a")
            assertEquals("AQueryBuilder", name)
        }
    }

    @Nested
    @DisplayName("Complex Query Field Scenarios")
    inner class ComplexScenariosTests {

        @Test
        fun `handles multiple input arguments`() {
            val result = generateQueryFieldDsl(
                """
                type Query {
                    searchCharacter(search: CharacterSearchInput!, filter: FilterInput): [Character]
                }
                input CharacterSearchInput {
                    name: String
                }
                input FilterInput {
                    status: String
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "searchCharacter"
            ).toString()

            assertTrue(result.contains("fun search(block: CharacterSearchInputBuilder.() -> Unit)"))
            assertTrue(result.contains("fun filter(block: FilterInputBuilder.() -> Unit)"))
        }

        @Test
        fun `handles input with scalar arguments`() {
            val result = generateQueryFieldDsl(
                """
                type Query {
                    searchCharacter(search: CharacterSearchInput!, limit: Int): [Character]
                }
                input CharacterSearchInput {
                    name: String
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "searchCharacter"
            ).toString()

            assertTrue(result.contains("fun search(block: CharacterSearchInputBuilder.() -> Unit)"))
            assertTrue(result.contains("var limit: Int?"))
        }

        @Test
        fun `returns interface type extends interface builder`() {
            val result = generateQueryFieldDsl(
                """
                type Query {
                    searchNode(search: SearchInput!): Node
                }
                input SearchInput {
                    id: ID
                }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                    name: String
                }
                """.trimIndent(),
                "searchNode"
            ).toString()

            assertTrue(result.contains(": NodeDslBuilder()"))
        }
    }

    @Nested
    @DisplayName("Nullable Input Arguments")
    inner class NullableInputTests {

        @Test
        fun `generates function for nullable input argument`() {
            val result = generateQueryFieldDsl(
                """
                type Query {
                    searchCharacter(search: CharacterSearchInput): [Character]
                }
                input CharacterSearchInput {
                    name: String
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "searchCharacter"
            ).toString()

            // Still generates as function since it's an input type
            assertTrue(result.contains("fun search(block: CharacterSearchInputBuilder.() -> Unit)"))
        }
    }
}
