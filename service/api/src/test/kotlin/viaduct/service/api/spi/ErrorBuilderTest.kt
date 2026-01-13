package viaduct.service.api.spi

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import viaduct.graphql.SourceLocation

class ErrorBuilderTest {
    @Test
    fun `test build error with message only`() {
        val error = ErrorBuilder
            .newError()
            .message("Test error")
            .build()

        assertEquals("Test error", error.message)
        assertNull(error.path)
        assertNull(error.locations)
        assertNull(error.extensions)
    }

    @Test
    fun `test build error with all fields`() {
        val location = SourceLocation(line = 10, column = 5)
        val error = ErrorBuilder
            .newError()
            .message("User not found")
            .path(listOf("user", "profile", 0))
            .location(location)
            .extension("errorType", "NOT_FOUND")
            .extension("localizedMessage", "User was not found")
            .build()

        assertEquals("User not found", error.message)
        assertEquals(listOf("user", "profile", 0), error.path)
        assertEquals(1, error.locations?.size)
        assertEquals(location, error.locations?.first())
        assertEquals("NOT_FOUND", error.extensions?.get("errorType"))
        assertEquals("User was not found", error.extensions?.get("localizedMessage"))
    }

    @Test
    fun `test build error with multiple locations`() {
        val location1 = SourceLocation(line = 1, column = 1)
        val location2 = SourceLocation(line = 5, column = 10)
        val error = ErrorBuilder
            .newError()
            .message("Multiple errors")
            .locations(listOf(location1, location2))
            .build()

        assertEquals(2, error.locations?.size)
        assertEquals(location1, error.locations?.get(0))
        assertEquals(location2, error.locations?.get(1))
    }

    @Test
    fun `test build error with extensions map`() {
        val extensions = mapOf(
            "errorType" to "VALIDATION",
            "field" to "email",
            "code" to 400
        )
        val error = ErrorBuilder
            .newError()
            .message("Validation error")
            .extensions(extensions)
            .build()

        assertEquals("VALIDATION", error.extensions?.get("errorType"))
        assertEquals("email", error.extensions?.get("field"))
        assertEquals(400, error.extensions?.get("code"))
    }

    @Test
    fun `test build error with combined extension methods`() {
        val error = ErrorBuilder
            .newError()
            .message("Error")
            .extensions(mapOf("key1" to "value1"))
            .extension("key2", "value2")
            .extension("key3", "value3")
            .build()

        assertNotNull(error.extensions)
        assertEquals("value1", error.extensions?.get("key1"))
        assertEquals("value2", error.extensions?.get("key2"))
        assertEquals("value3", error.extensions?.get("key3"))
    }

    @Test
    fun `test builder method chaining returns same instance`() {
        val builder = ErrorBuilder
            .newError()
        val result1 = builder.message("Test")
        val result2 = result1.path(listOf("field"))
        val result3 = result2.extension("key", "value")

        assertEquals(builder, result1)
        assertEquals(result1, result2)
        assertEquals(result2, result3)
    }

    @Test
    fun `test newError with Metadata populates path and location`() {
        val sourceLocation = SourceLocation(line = 15, column = 20)
        val metadata = ErrorReporter.Metadata(
            executionPath = listOf("query", "user", 0, "name"),
            sourceLocation = sourceLocation
        )

        val error = ErrorBuilder
            .newError(metadata)
            .message("Error with metadata")
            .build()

        assertEquals(listOf("query", "user", 0, "name"), error.path)
        assertEquals(1, error.locations?.size)
        assertEquals(sourceLocation, error.locations?.first())
    }

    @Test
    fun `test newError with empty Metadata`() {
        val metadata = ErrorReporter.Metadata.EMPTY
        val error = ErrorBuilder
            .newError(metadata)
            .message("Error with empty metadata")
            .build()

        assertEquals("Error with empty metadata", error.message)
        assertNull(error.path)
        assertNull(error.locations)
    }

    @Test
    fun `test extension overwrites previous value for same key`() {
        val error = ErrorBuilder
            .newError()
            .message("Test")
            .extension("key", "value1")
            .extension("key", "value2")
            .build()

        assertEquals("value2", error.extensions?.get("key"))
    }

    @Test
    fun `test location overwrites locations`() {
        val location1 = SourceLocation(1, 1)
        val location2 = SourceLocation(2, 2)
        val location3 = SourceLocation(3, 3)

        val error = ErrorBuilder
            .newError()
            .message("Test")
            .locations(listOf(location1, location2))
            .location(location3)
            .build()

        assertEquals(1, error.locations?.size)
        assertEquals(location3, error.locations?.first())
    }

    @Test
    fun `test empty extensions are null in result`() {
        val error = ErrorBuilder
            .newError()
            .message("Test")
            .build()

        assertNull(error.extensions)
    }

    @Test
    fun `test extensions with one entry are not null`() {
        val error = ErrorBuilder
            .newError()
            .message("Test")
            .extension("key", "value")
            .build()

        assertNotNull(error.extensions)
        assertEquals(1, error.extensions?.size)
    }
}
