package viaduct.service.api.spi

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.jupiter.api.Test

class ViaductErrorBuilderTest {
    @Test
    fun `test build error with all fields`() {
        val error = ViaductErrorBuilder.newError()
            .message("User not found")
            .path(listOf("user", "profile", "name"))
            .location(SourceLocation(line = 5, column = 10, sourceName = "query.graphql"))
            .errorType("NOT_FOUND")
            .fatal(false)
            .extension("customField", "customValue")
            .build()

        assertEquals("User not found", error.message)
        assertEquals(listOf("user", "profile", "name"), error.path)
        assertEquals(1, error.locations?.size)
        assertEquals(5, error.locations!![0].line)
        assertEquals("NOT_FOUND", error.errorType)
        assertEquals(false, error.fatal)
        assertEquals("customValue", error.extensions?.get("customField"))
    }

    @Test
    fun `test build error with only message`() {
        val error = ViaductErrorBuilder.newError()
            .message("Simple error")
            .build()

        assertEquals("Simple error", error.message)
        assertNull(error.path)
        assertNull(error.locations)
        assertNull(error.errorType)
        assertNull(error.fatal)
        assertNull(error.extensions)
    }

    @Test
    fun `test build error from metadata`() {
        val metadata = ErrorMetadata(
            fieldName = "name",
            parentType = "User",
            executionPath = listOf("user", "name"),
            sourceLocation = SourceLocation(line = 10, column = 5)
        )

        val error = ViaductErrorBuilder.newError(metadata)
            .message("Error from metadata")
            .build()

        assertEquals("Error from metadata", error.message)
        assertEquals(listOf("user", "name"), error.path)
        assertEquals(1, error.locations?.size)
        assertEquals(10, error.locations!![0].line)
    }

    @Test
    fun `test build error with multiple locations`() {
        val locations = listOf(
            SourceLocation(line = 1, column = 1),
            SourceLocation(line = 5, column = 10)
        )

        val error = ViaductErrorBuilder.newError()
            .message("Error with multiple locations")
            .locations(locations)
            .build()

        assertEquals(2, error.locations?.size)
    }

    @Test
    fun `test build error with extensions map`() {
        val extensions = mapOf(
            "errorType" to "VALIDATION",
            "code" to 400
        )

        val error = ViaductErrorBuilder.newError()
            .message("Validation error")
            .extensions(extensions)
            .extension("extra", "value")
            .build()

        assertEquals("VALIDATION", error.extensions?.get("errorType"))
        assertEquals(400, error.extensions?.get("code"))
        assertEquals("value", error.extensions?.get("extra"))
    }

    @Test
    fun `test builder is fluent`() {
        val builder = ViaductErrorBuilder.newError()
        val result1 = builder.message("test")
        val result2 = result1.path(listOf("path"))
        val result3 = result2.extension("key", "value")
        val result4 = result3.errorType("ERROR")
        val result5 = result4.fatal(true)

        assertSame(builder, result1)
        assertSame(builder, result2)
        assertSame(builder, result3)
        assertSame(builder, result4)
        assertSame(builder, result5)
    }

    @Test
    fun `test build error with errorType first-class method`() {
        val error = ViaductErrorBuilder.newError()
            .message("Not found")
            .errorType("NOT_FOUND")
            .build()

        assertEquals("NOT_FOUND", error.errorType)
        assertNull(error.fatal)
    }

    @Test
    fun `test build error with fatal first-class method`() {
        val error = ViaductErrorBuilder.newError()
            .message("Critical error")
            .fatal(true)
            .build()

        assertEquals(true, error.fatal)
        assertNull(error.errorType)
    }

    @Test
    fun `test build error with both errorType and fatal`() {
        val error = ViaductErrorBuilder.newError()
            .message("Validation failed")
            .errorType("VALIDATION_ERROR")
            .fatal(false)
            .build()

        assertEquals("VALIDATION_ERROR", error.errorType)
        assertEquals(false, error.fatal)
    }

    @Test
    fun `test errorType and fatal are separate from extensions`() {
        val error = ViaductErrorBuilder.newError()
            .message("Error")
            .errorType("TIMEOUT")
            .fatal(true)
            .extension("customField", "value")
            .build()

        assertEquals("TIMEOUT", error.errorType)
        assertEquals(true, error.fatal)
        assertEquals("value", error.extensions?.get("customField"))
        assertNull(error.extensions?.get("errorType"))
        assertNull(error.extensions?.get("fatal"))
    }
}
