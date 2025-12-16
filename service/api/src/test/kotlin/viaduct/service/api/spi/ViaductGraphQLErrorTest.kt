package viaduct.service.api.spi

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ViaductGraphQLErrorTest {
    @Test
    fun `test error with all fields`() {
        val error = ViaductGraphQLError(
            message = "User not found",
            path = listOf("user", "profile", "name"),
            locations = listOf(SourceLocation(line = 5, column = 10)),
            errorType = "NOT_FOUND",
            fatal = false,
            extensions = mapOf("customField" to "customValue")
        )

        assertEquals("User not found", error.message)
        assertEquals(listOf("user", "profile", "name"), error.path)
        assertEquals(1, error.locations?.size)
        assertEquals(5, error.locations!![0].line)
        assertEquals("NOT_FOUND", error.errorType)
        assertEquals(false, error.fatal)
        assertEquals("customValue", error.extensions?.get("customField"))
    }

    @Test
    fun `test error with only message`() {
        val error = ViaductGraphQLError(message = "Simple error")

        assertEquals("Simple error", error.message)
        assertNull(error.path)
        assertNull(error.locations)
        assertNull(error.errorType)
        assertNull(error.fatal)
        assertNull(error.extensions)
    }

    @Test
    fun `test error with path containing mixed types`() {
        val error = ViaductGraphQLError(
            message = "Error in list item",
            path = listOf("items", 0, "name")
        )

        assertEquals(listOf("items", 0, "name"), error.path)
    }

    @Test
    fun `test error with multiple locations`() {
        val locations = listOf(
            SourceLocation(line = 1, column = 1),
            SourceLocation(line = 5, column = 10, sourceName = "fragment.graphql")
        )
        val error = ViaductGraphQLError(
            message = "Error at multiple locations",
            locations = locations
        )

        assertEquals(2, error.locations?.size)
    }

    @Test
    fun `test error equality`() {
        val error1 = ViaductGraphQLError(
            message = "Error",
            path = listOf("field"),
            extensions = mapOf("key" to "value")
        )
        val error2 = ViaductGraphQLError(
            message = "Error",
            path = listOf("field"),
            extensions = mapOf("key" to "value")
        )

        assertEquals(error1, error2)
    }

    @Test
    fun `test error with errorType as first-class field`() {
        val error = ViaductGraphQLError(
            message = "Not found",
            errorType = "NOT_FOUND"
        )

        assertEquals("NOT_FOUND", error.errorType)
        assertNull(error.fatal)
    }

    @Test
    fun `test error with fatal as first-class field`() {
        val error = ViaductGraphQLError(
            message = "Critical error",
            fatal = true
        )

        assertEquals(true, error.fatal)
        assertNull(error.errorType)
    }

    @Test
    fun `test error with both errorType and fatal`() {
        val error = ViaductGraphQLError(
            message = "Validation failed",
            errorType = "VALIDATION_ERROR",
            fatal = false
        )

        assertEquals("VALIDATION_ERROR", error.errorType)
        assertEquals(false, error.fatal)
    }

    @Test
    fun `test errorType and fatal are independent from extensions`() {
        val error = ViaductGraphQLError(
            message = "Error",
            errorType = "TIMEOUT",
            fatal = true,
            extensions = mapOf("customField" to "value")
        )

        assertEquals("TIMEOUT", error.errorType)
        assertEquals(true, error.fatal)
        assertEquals("value", error.extensions?.get("customField"))
        assertNull(error.extensions?.get("errorType"))
        assertNull(error.extensions?.get("fatal"))
    }
}
