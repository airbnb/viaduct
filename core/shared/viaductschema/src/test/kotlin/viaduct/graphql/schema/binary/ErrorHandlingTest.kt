package viaduct.graphql.schema.binary

import graphql.schema.idl.SchemaParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.binary.extensions.fromBinaryFile
import viaduct.graphql.schema.binary.extensions.toBinaryFile
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

/**
 * Tests for error handling during encoding and BSchema runtime operations.
 *
 * Note: Decoding error tests have been consolidated in [DecodingErrorHandlingTest].
 * This file contains tests for:
 * - Encoding-time errors
 * - BInputStream configuration errors
 * - Runtime access errors (e.g., accessing defaultValue when hasDefault is false)
 */
class ErrorHandlingTest {
    companion object {
        fun assertMessageContains(
            expectedContents: String,
            exception: Throwable
        ) {
            exception.message!!.let { m ->
                assert(m.contains(expectedContents), { m })
            }
        }
    }

    // ========================================================================
    // Encoding-time errors
    // ========================================================================

    @Test
    fun `Type expression with excessive list depth throws exception during encoding`() {
        // Create a type expression with 28+ levels of list nesting
        val deeplyNested = "[".repeat(28) + "Int" + "]".repeat(28)
        val sdl = "type Query { field: $deeplyNested }"

        val tdr = SchemaParser().parse("$builtins\n$sdl")
        val schema = ViaductSchema.fromTypeDefinitionRegistry(tdr)

        val baos = ByteArrayOutputStream()
        val exception = assertThrows<IllegalArgumentException> {
            schema.toBinaryFile(baos)
        }
        assertMessageContains("Max list depth exceeded", exception)
    }

    // ========================================================================
    // BInputStream configuration errors
    // ========================================================================

    @Test
    fun `BInputStream constructor with negative maxStringLength throws exception`() {
        val baos = ByteArrayOutputStream()
        baos.write(byteArrayOf(1, 2, 3, 4))
        val input = ByteArrayInputStream(baos.toByteArray())

        val exception = assertThrows<IllegalArgumentException> {
            BInputStream(input, -1)
        }
        assertMessageContains("maxStringLength must be at least", exception)
    }

    @Test
    fun `BInputStream constructor with excessive maxStringLength throws exception`() {
        val baos = ByteArrayOutputStream()
        baos.write(byteArrayOf(1, 2, 3, 4))
        val input = ByteArrayInputStream(baos.toByteArray())

        val exception = assertThrows<IllegalArgumentException> {
            BInputStream(input, 200_000) // Exceeds 128KB buffer
        }
        assertMessageContains("maxStringLength may not be larger than", exception)
    }

    // ========================================================================
    // Runtime access errors (accessing defaultValue when hasDefault is false)
    // ========================================================================

    @Test
    fun `Accessing default value when hasDefault is false throws exception for field argument`() {
        val sdl = """
            type Query {
                field(arg: String): String
            }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            ViaductSchema.fromTypeDefinitionRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        schema.toBinaryFile(tmp)
        val bschema = ViaductSchema.fromBinaryFile(ByteArrayInputStream(tmp.toByteArray()))

        val queryType = bschema.types["Query"] as ViaductSchema.Object
        val field = queryType.fields.first { it.name == "field" }
        val arg = field.args.first()

        assertEquals(false, arg.hasDefault)
        assertThrows<NoSuchElementException> {
            arg.defaultValue
        }
    }

    @Test
    fun `Accessing default value when hasDefault is false throws exception for directive argument`() {
        val sdl = """
            directive @example(arg: String) on FIELD_DEFINITION

            type Query {
                field: String
            }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            ViaductSchema.fromTypeDefinitionRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        schema.toBinaryFile(tmp)
        val bschema = ViaductSchema.fromBinaryFile(ByteArrayInputStream(tmp.toByteArray()))

        val directive = bschema.directives["example"]!!
        val arg = directive.args.first()

        assertEquals(false, arg.hasDefault)
        assertThrows<NoSuchElementException> {
            arg.defaultValue
        }
    }

    @Test
    fun `Accessing default value when hasDefault is false throws exception for input field`() {
        val sdl = """
            input UserInput {
                name: String
            }
            type Query {
                user(input: UserInput): String
            }
        """.trimIndent()

        val schema = run {
            val tdr = SchemaParser().parse("$builtins\n$sdl")
            ViaductSchema.fromTypeDefinitionRegistry(tdr)
        }

        val tmp = ByteArrayOutputStream()
        schema.toBinaryFile(tmp)
        val bschema = ViaductSchema.fromBinaryFile(ByteArrayInputStream(tmp.toByteArray()))

        val inputType = bschema.types["UserInput"] as ViaductSchema.Input
        val field = inputType.fields.first { it.name == "name" }

        assertEquals(false, field.hasDefault)
        assertThrows<NoSuchElementException> {
            field.defaultValue
        }
    }
}
