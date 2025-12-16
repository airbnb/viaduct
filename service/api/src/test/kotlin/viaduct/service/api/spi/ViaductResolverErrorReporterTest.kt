package viaduct.service.api.spi

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ViaductResolverErrorReporterTest {
    @Test
    fun `test NoOp does not throw`() {
        val reporter = ViaductResolverErrorReporter.NoOp

        // Should not throw
        reporter.reportError(
            RuntimeException("test error"),
            "Test error message",
            ErrorMetadata(fieldName = "test")
        )
    }

    @Test
    fun `test custom implementation receives all parameters`() {
        var capturedExceptionMessage: String? = null
        var capturedErrorMessage: String? = null
        var capturedMetadata: ErrorMetadata? = null

        val reporter = ViaductResolverErrorReporter { exception, errorMessage, metadata ->
            capturedExceptionMessage = exception.message
            capturedErrorMessage = errorMessage
            capturedMetadata = metadata
        }

        val metadata = ErrorMetadata(
            fieldName = "name",
            parentType = "User",
            operationName = "GetUser",
            executionPath = listOf("user", "name"),
            context = "test-context",
            localContext = "test-local-context",
            componentName = "user-service"
        )

        reporter.reportError(
            RuntimeException("Test exception"),
            "Error fetching user.name",
            metadata
        )

        assertEquals("Test exception", capturedExceptionMessage)
        assertEquals("Error fetching user.name", capturedErrorMessage)
        assertNotNull(capturedMetadata)
        assertEquals("name", capturedMetadata?.fieldName)
        assertEquals("User", capturedMetadata?.parentType)
        assertEquals("GetUser", capturedMetadata?.operationName)
        assertEquals("test-context", capturedMetadata?.context)
        assertEquals("test-local-context", capturedMetadata?.localContext)
        assertEquals("user-service", capturedMetadata?.componentName)
    }

    @Test
    fun `test functional interface with lambda`() {
        var reportCalled = false

        val reporter: ViaductResolverErrorReporter = ViaductResolverErrorReporter { _, _, _ ->
            reportCalled = true
        }

        reporter.reportError(
            RuntimeException(),
            "test message",
            ErrorMetadata.EMPTY
        )

        assertTrue(reportCalled)
    }

    @Test
    fun `test reporter can access context fields for monitoring`() {
        var extractedContext: Any? = null
        var extractedComponentName: String? = null

        val reporter = ViaductResolverErrorReporter { _, _, metadata ->
            extractedContext = metadata.context
            extractedComponentName = metadata.componentName
        }

        val contextObject = mapOf("requestId" to "123", "userId" to "456")
        val metadata = ErrorMetadata(
            context = contextObject,
            componentName = "my-component"
        )

        reporter.reportError(RuntimeException(), "error", metadata)

        assertEquals(contextObject, extractedContext)
        assertEquals("my-component", extractedComponentName)
    }
}
