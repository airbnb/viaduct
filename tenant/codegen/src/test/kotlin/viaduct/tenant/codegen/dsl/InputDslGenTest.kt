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

class InputDslGenTest {

    private fun generateInputDsl(sdl: String, inputName: String): STContents {
        val schema = mkSchema(sdl)
        val baseTypeMapper = ViaductBaseTypeMapper(schema)
        val inputType = schema.types[inputName]!! as ViaductSchema.Input
        return inputDslGen(TestPackages.DSL_PACKAGE, inputType, baseTypeMapper)
    }

    @Nested
    @DisplayName("Builder Class Generation")
    inner class BuilderClassTests {

        @Test
        fun `generates named builder class`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input UserInput {
                    name: String
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            assertTrue(result.contains("class UserInputBuilder internal constructor()"))
        }

        @Test
        fun `generates private values map`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input UserInput {
                    name: String
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            assertTrue(result.contains("private val values = mutableMapOf<String, Any?>()"))
        }

        @Test
        fun `generates build method returning map`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input UserInput {
                    name: String
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            assertTrue(result.contains("internal fun build(): Map<String, Any?> = values.toMap()"))
        }
    }

    @Nested
    @DisplayName("Scalar Field Generation")
    inner class ScalarFieldTests {

        @Test
        fun `generates String field as property`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input UserInput {
                    name: String
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            assertTrue(result.contains("var name: String?"))
            assertTrue(result.contains("values[\"name\"]"))
        }

        @Test
        fun `generates Int field as property`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input UserInput {
                    age: Int
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            assertTrue(result.contains("var age: Int?"))
        }

        @Test
        fun `generates non-nullable fields`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input UserInput {
                    name: String!
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            assertTrue(result.contains("var name: String"))
            // Should not have nullable String? but String
            assertFalse(result.contains("var name: String?"))
        }

        @Test
        fun `generates Boolean field as property`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input UserInput {
                    active: Boolean
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            assertTrue(result.contains("var active: Boolean?"))
        }

        @Test
        fun `generates Float field as property`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input UserInput {
                    score: Float
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            assertTrue(result.contains("var score:"))
        }

        @Test
        fun `generates enum field as property`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                enum Status { ACTIVE INACTIVE }
                input UserInput {
                    status: Status
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            assertTrue(result.contains("var status:"))
        }

        @Test
        fun `generates list field as property`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input UserInput {
                    tags: [String]
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            assertTrue(result.contains("var tags:"))
            assertTrue(result.contains("List"))
        }
    }

    @Nested
    @DisplayName("Nested Input Field Generation")
    inner class NestedInputFieldTests {

        @Test
        fun `generates nested input as function`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input AddressInput {
                    street: String
                }
                input UserInput {
                    address: AddressInput
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            assertTrue(result.contains("fun address(block: AddressInputBuilder.() -> Unit)"))
        }

        @Test
        fun `generates nested builder invocation`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input AddressInput {
                    street: String
                }
                input UserInput {
                    address: AddressInput
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            assertTrue(result.contains("val nestedBuilder = AddressInputBuilder()"))
            assertTrue(result.contains("nestedBuilder.block()"))
            assertTrue(result.contains("values[\"address\"] = nestedBuilder.build()"))
        }

        @Test
        fun `handles multiple nested inputs`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input AddressInput {
                    street: String
                }
                input PhoneInput {
                    number: String
                }
                input UserInput {
                    address: AddressInput
                    phone: PhoneInput
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            assertTrue(result.contains("fun address(block: AddressInputBuilder.() -> Unit)"))
            assertTrue(result.contains("fun phone(block: PhoneInputBuilder.() -> Unit)"))
        }
    }

    @Nested
    @DisplayName("Mixed Fields Generation")
    inner class MixedFieldsTests {

        @Test
        fun `generates both scalar and nested input fields`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input AddressInput {
                    street: String
                }
                input UserInput {
                    name: String
                    age: Int
                    address: AddressInput
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            // Scalars as properties
            assertTrue(result.contains("var name: String?"))
            assertTrue(result.contains("var age: Int?"))
            // Nested as function
            assertTrue(result.contains("fun address(block: AddressInputBuilder.() -> Unit)"))
        }
    }

    @Nested
    @DisplayName("Reserved Keyword Handling")
    inner class ReservedKeywordTests {

        @Test
        fun `escapes reserved Kotlin keywords`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input UserInput {
                    class: String
                    object: String
                    when: String
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            // Should escape reserved keywords with backticks
            assertTrue(result.contains("`class`") || result.contains("values[\"class\"]"))
            assertTrue(result.contains("`object`") || result.contains("values[\"object\"]"))
            assertTrue(result.contains("`when`") || result.contains("values[\"when\"]"))
        }
    }

    @Nested
    @DisplayName("Package Generation")
    inner class PackageTests {

        @Test
        fun `generates correct package declaration`() {
            val result = generateInputDsl(
                """
                type Query { test: String }
                input UserInput {
                    name: String
                }
                """.trimIndent(),
                "UserInput"
            ).toString()

            assertTrue(result.contains("package ${TestPackages.DSL_PACKAGE}"))
        }
    }
}
