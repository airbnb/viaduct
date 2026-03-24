package viaduct.service.api

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import viaduct.graphql.SourceLocation

class GraphQLErrorTest {
    @Test
    fun `test create error with all fields`() {
        val error = GraphQLError(
            message = "User not found",
            path = listOf("user", "profile", 0),
            locations = listOf(SourceLocation(line = 10, column = 5)),
            extensions = mapOf("errorType" to "NOT_FOUND", "localizedMessage" to "User was not found")
        )

        assertEquals("User not found", error.message)
        assertEquals(listOf("user", "profile", 0), error.path)
        assertEquals(1, error.locations?.size)
        assertEquals(10, error.locations?.first()?.line)
        assertEquals(5, error.locations?.first()?.column)
        assertEquals("NOT_FOUND", error.extensions.get("errorType"))
        assertEquals("User was not found", error.extensions.get("localizedMessage"))
    }

    @Test
    fun `test create error with only required fields`() {
        val error = GraphQLError(message = "Something went wrong")

        assertEquals("Something went wrong", error.message)
        assertNull(error.path)
        assertNull(error.locations)
        assertEquals(emptyMap(), error.extensions)
    }

    @Test
    fun `test error data class equality`() {
        val error1 = GraphQLError(
            message = "Error",
            path = listOf("field"),
            locations = listOf(SourceLocation(1, 1))
        )
        val error2 = GraphQLError(
            message = "Error",
            path = listOf("field"),
            locations = listOf(SourceLocation(1, 1))
        )
        val error3 = GraphQLError(
            message = "Different error",
            path = listOf("field"),
            locations = listOf(SourceLocation(1, 1))
        )

        assertEquals(error1, error2)
        assert(error1 != error3)
    }

    @Test
    fun `test error copy`() {
        val original = GraphQLError(
            message = "Original error",
            path = listOf("user"),
            extensions = mapOf("code" to "ERR001")
        )
        val copied = original.copy(message = "Copied error")

        assertEquals("Copied error", copied.message)
        assertEquals(listOf("user"), copied.path)
        assertEquals(mapOf("code" to "ERR001"), copied.extensions)
    }

    @Test
    fun `test error with multiple locations`() {
        val error = GraphQLError(
            message = "Multiple locations",
            locations = listOf(
                SourceLocation(line = 1, column = 5),
                SourceLocation(line = 3, column = 10),
                SourceLocation(line = 7, column = 1)
            )
        )

        assertEquals(3, error.locations?.size)
        assertEquals(1, error.locations?.get(0)?.line)
        assertEquals(3, error.locations?.get(1)?.line)
        assertEquals(7, error.locations?.get(2)?.line)
    }

    @Test
    fun `test error with nested path`() {
        val error = GraphQLError(
            message = "Nested path error",
            path = listOf("query", "users", 0, "addresses", 2, "street")
        )

        assertEquals(6, error.path?.size)
        assertEquals("query", error.path?.get(0))
        assertEquals(0, error.path?.get(2))
        assertEquals("street", error.path?.get(5))
    }
}
