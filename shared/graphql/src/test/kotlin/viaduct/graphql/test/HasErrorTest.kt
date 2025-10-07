package viaduct.graphql.test

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HasErrorTest {
    @Test
    fun `assertHasError success cases`() {
        // Single error with matching message
        mapOf("errors" to listOf(mapOf("message" to "Database connection failed")))
            .assertHasError("Database")

        // Multiple errors, first one matches
        mapOf(
            "errors" to listOf(
                mapOf("message" to "Validation error: missing field"),
                mapOf("message" to "Other error")
            )
        ).assertHasError("Validation")

        // Multiple errors, second one matches
        mapOf(
            "errors" to listOf(
                mapOf("message" to "Some other error"),
                mapOf("message" to "Network timeout occurred")
            )
        ).assertHasError("timeout")

        // Partial string matching
        mapOf("errors" to listOf(mapOf("message" to "The user authentication has failed due to invalid credentials")))
            .assertHasError("authentication")
    }

    @Test
    fun `assertHasError failure cases`() {
        // No errors key at all
        assertThrows<AssertionError> {
            mapOf("data" to "some data").assertHasError("any")
        }

        // Null errors
        assertThrows<AssertionError> {
            mapOf("errors" to null).assertHasError("any")
        }

        // Empty errors list
        assertThrows<AssertionError> {
            mapOf("errors" to emptyList<Any>()).assertHasError("any")
        }

        // Errors but no matching message
        assertThrows<AssertionError> {
            mapOf("errors" to listOf(mapOf("message" to "Wrong error type")))
                .assertHasError("Database")
        }

        // Errors with non-string messages (should be filtered out)
        assertThrows<AssertionError> {
            mapOf(
                "errors" to listOf(
                    mapOf("message" to 123), // Not a string
                    mapOf("other" to "field") // No message field
                )
            ).assertHasError("any")
        }
    }
}
