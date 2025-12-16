package viaduct.service.api.spi

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ViaductResolverErrorBuilderTest {
    @Test
    fun `test NoOp returns null for any exception`() {
        val builder = ViaductResolverErrorBuilder.NoOp
        val metadata = ErrorMetadata(fieldName = "test")

        val result = builder.exceptionToGraphQLError(
            RuntimeException("test error"),
            metadata
        )

        assertNull(result)
    }

    @Test
    fun `test custom implementation can handle specific exceptions`() {
        val builder = ViaductResolverErrorBuilder { throwable, _ ->
            when (throwable) {
                is IllegalArgumentException -> listOf(
                    ViaductGraphQLError(
                        message = "Invalid argument: ${throwable.message}",
                        extensions = mapOf("errorType" to "INVALID_ARGUMENT")
                    )
                )
                else -> null
            }
        }

        val illegalArgResult = builder.exceptionToGraphQLError(
            IllegalArgumentException("bad value"),
            ErrorMetadata.EMPTY
        )

        assertNotNull(illegalArgResult)
        assertEquals(1, illegalArgResult.size)
        assertEquals("Invalid argument: bad value", illegalArgResult[0].message)
        assertEquals("INVALID_ARGUMENT", illegalArgResult[0].extensions?.get("errorType"))

        val otherResult = builder.exceptionToGraphQLError(
            RuntimeException("other error"),
            ErrorMetadata.EMPTY
        )

        assertNull(otherResult)
    }

    @Test
    fun `test builder can access all metadata fields`() {
        val metadata = ErrorMetadata(
            fieldName = "name",
            parentType = "User",
            operationName = "GetUser",
            executionPath = listOf("user", "name"),
            sourceLocation = SourceLocation(line = 5, column = 10),
            context = "test-context",
            localContext = "test-local-context",
            componentName = "user-service"
        )

        var capturedMetadata: ErrorMetadata? = null
        val builder = ViaductResolverErrorBuilder { _, meta ->
            capturedMetadata = meta
            null
        }

        builder.exceptionToGraphQLError(RuntimeException(), metadata)

        assertNotNull(capturedMetadata)
        assertEquals("name", capturedMetadata?.fieldName)
        assertEquals("User", capturedMetadata?.parentType)
        assertEquals("GetUser", capturedMetadata?.operationName)
        assertEquals(listOf("user", "name"), capturedMetadata?.executionPath)
        assertEquals("test-context", capturedMetadata?.context)
        assertEquals("test-local-context", capturedMetadata?.localContext)
        assertEquals("user-service", capturedMetadata?.componentName)
    }

    @Test
    fun `test functional interface with lambda`() {
        val builder: ViaductResolverErrorBuilder = ViaductResolverErrorBuilder { _, _ ->
            listOf(ViaductGraphQLError(message = "Lambda error"))
        }

        val result = builder.exceptionToGraphQLError(RuntimeException(), ErrorMetadata.EMPTY)

        assertNotNull(result)
        assertEquals("Lambda error", result[0].message)
    }
}
