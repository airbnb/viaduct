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

class MutationFieldDslGenTest {

    private fun generateMutationFieldDsl(sdl: String, fieldName: String): STContents {
        val schema = mkSchema(sdl)
        val baseTypeMapper = ViaductBaseTypeMapper(schema)
        val mutationDef = schema.types["Mutation"]!! as ViaductSchema.Object
        val field = mutationDef.fields.first { it.name == fieldName }
        val returnType = field.type.baseTypeDef
        return mutationFieldDslGen(TestPackages.DSL_PACKAGE, field, returnType, baseTypeMapper)
    }

    @Nested
    @DisplayName("Mutation Field Builder Class")
    inner class BuilderClassTests {

        @Test
        fun `generates mutation builder with correct name`() {
            val result = generateMutationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createCharacter(input: CreateCharacterInput!): Character
                }
                input CreateCharacterInput {
                    name: String!
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "createCharacter"
            ).toString()

            assertTrue(result.contains("class CreateCharacterMutationBuilder"))
        }

        @Test
        fun `extends return type builder`() {
            val result = generateMutationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createCharacter(input: CreateCharacterInput!): Character
                }
                input CreateCharacterInput {
                    name: String!
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "createCharacter"
            ).toString()

            assertTrue(result.contains(": CharacterDslBuilder()"))
        }

        @Test
        fun `has internal constructor`() {
            val result = generateMutationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createCharacter(input: CreateCharacterInput!): Character
                }
                input CreateCharacterInput {
                    name: String!
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "createCharacter"
            ).toString()

            assertTrue(result.contains("internal constructor()"))
        }
    }

    @Nested
    @DisplayName("Input Arguments as DSL Functions")
    inner class InputArgDslTests {

        @Test
        fun `generates DSL function for input argument`() {
            val result = generateMutationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createCharacter(input: CreateCharacterInput!): Character
                }
                input CreateCharacterInput {
                    name: String!
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "createCharacter"
            ).toString()

            assertTrue(result.contains("fun input(block: CreateCharacterInputBuilder.() -> Unit)"))
        }

        @Test
        fun `stores input in argValues map`() {
            val result = generateMutationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createCharacter(input: CreateCharacterInput!): Character
                }
                input CreateCharacterInput {
                    name: String!
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "createCharacter"
            ).toString()

            assertTrue(result.contains("argValues[\"input\"] = inputBuilder.build()"))
        }
    }

    @Nested
    @DisplayName("getMutationFieldBuilderName Helper")
    inner class BuilderNameHelperTests {

        @Test
        fun `generates correct builder name for simple field`() {
            val name = getMutationFieldBuilderName("create")
            assertEquals("CreateMutationBuilder", name)
        }

        @Test
        fun `generates correct builder name for camelCase field`() {
            val name = getMutationFieldBuilderName("createCharacter")
            assertEquals("CreateCharacterMutationBuilder", name)
        }

        @Test
        fun `capitalizes first letter`() {
            val name = getMutationFieldBuilderName("x")
            assertEquals("XMutationBuilder", name)
        }

        @Test
        fun `handles update mutation name`() {
            val name = getMutationFieldBuilderName("updateUser")
            assertEquals("UpdateUserMutationBuilder", name)
        }

        @Test
        fun `handles delete mutation name`() {
            val name = getMutationFieldBuilderName("deleteUser")
            assertEquals("DeleteUserMutationBuilder", name)
        }
    }

    @Nested
    @DisplayName("Complex Mutation Field Scenarios")
    inner class ComplexScenariosTests {

        @Test
        fun `handles multiple input arguments`() {
            val result = generateMutationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createCharacter(input: CreateCharacterInput!, options: OptionsInput): Character
                }
                input CreateCharacterInput {
                    name: String!
                }
                input OptionsInput {
                    notify: Boolean
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "createCharacter"
            ).toString()

            assertTrue(result.contains("fun input(block: CreateCharacterInputBuilder.() -> Unit)"))
            assertTrue(result.contains("fun options(block: OptionsInputBuilder.() -> Unit)"))
        }

        @Test
        fun `handles input with scalar arguments`() {
            val result = generateMutationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createCharacter(input: CreateCharacterInput!, dryRun: Boolean): Character
                }
                input CreateCharacterInput {
                    name: String!
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "createCharacter"
            ).toString()

            assertTrue(result.contains("fun input(block: CreateCharacterInputBuilder.() -> Unit)"))
            assertTrue(result.contains("var dryRun: Boolean?"))
        }

        @Test
        fun `handles required scalar arguments`() {
            val result = generateMutationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createCharacter(input: CreateCharacterInput!, version: Int!): Character
                }
                input CreateCharacterInput {
                    name: String!
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "createCharacter"
            ).toString()

            assertTrue(result.contains("var version: Int"))
        }
    }

    @Nested
    @DisplayName("Documentation Generation")
    inner class DocumentationTests {

        @Test
        fun `generates class documentation`() {
            val result = generateMutationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createCharacter(input: CreateCharacterInput!): Character
                }
                input CreateCharacterInput {
                    name: String!
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "createCharacter"
            ).toString()

            assertTrue(result.contains("DSL builder for the `createCharacter` mutation field"))
        }

        @Test
        fun `references return type in documentation`() {
            val result = generateMutationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createCharacter(input: CreateCharacterInput!): Character
                }
                input CreateCharacterInput {
                    name: String!
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "createCharacter"
            ).toString()

            assertTrue(result.contains("[Character]"))
        }
    }

    @Nested
    @DisplayName("Interface Return Types")
    inner class InterfaceReturnTypeTests {

        @Test
        fun `extends interface builder for interface return type`() {
            val result = generateMutationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createNode(input: CreateNodeInput!): Node
                }
                input CreateNodeInput {
                    type: String!
                }
                interface Node {
                    id: ID!
                }
                type User implements Node {
                    id: ID!
                    name: String
                }
                """.trimIndent(),
                "createNode"
            ).toString()

            assertTrue(result.contains(": NodeDslBuilder()"))
        }
    }

    @Nested
    @DisplayName("BuildArgs Method")
    inner class BuildArgsMethodTests {

        @Test
        fun `generates buildArgs method`() {
            val result = generateMutationFieldDsl(
                """
                type Query { test: String }
                type Mutation {
                    createCharacter(input: CreateCharacterInput!): Character
                }
                input CreateCharacterInput {
                    name: String!
                }
                type Character {
                    id: ID
                    name: String
                }
                """.trimIndent(),
                "createCharacter"
            ).toString()

            assertTrue(result.contains("internal fun buildArgs(): Map<String, Any?> = argValues.toMap()"))
        }
    }
}
