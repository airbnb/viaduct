package viaduct.graphql.schema.binary

import graphql.schema.idl.SchemaParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.graphqljava.GJSchemaRaw

/**
 * Tests for error handling during encoding and BSchema runtime operations.
 *
 * Note: Decoding error tests have been consolidated in [DecodingErrorHandlingTest].
 * This file contains tests for:
 * - Encoding-time errors
 * - BInputStream configuration errors
 * - Runtime lookup errors on BSchema
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
        val schema = GJSchemaRaw.fromRegistry(tdr)

        val baos = ByteArrayOutputStream()
        val exception = assertThrows<IllegalArgumentException> {
            writeBSchema(schema, baos)
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
    // Runtime lookup errors
    // ========================================================================

    @Test
    fun `findDirective throws exception when directive not found`() {
        val schema = BSchema()

        val exception = assertThrows<NoSuchElementException> {
            schema.findDirective("missingDirective")
        }
        assert(exception.message!!.contains("Directive def not found"))
        assert(exception.message!!.contains("missingDirective"))
    }
}
