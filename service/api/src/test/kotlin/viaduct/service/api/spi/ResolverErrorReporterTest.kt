package viaduct.service.api.spi

import graphql.schema.DataFetchingEnvironmentImpl
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.StaticDataFetcher
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class ResolverErrorReporterTest {
    @Test
    fun testToMap() {
        val metadata = ErrorMetadata(
            fieldName = "testField",
            parentType = "TestType",
            operationName = "TestOperation",
            isFrameworkError = true,
            resolvers = listOf("Resolver1", "Resolver2"),
            errorType = "SomeErrorType"
        )
        val map = metadata.toMap()

        assertEquals("testField", map["fieldName"])
        assertEquals("TestType", map["parentType"])
        assertEquals("TestOperation", map["operationName"])
        assertEquals("true", map["isFrameworkError"])
        assertEquals(listOf("Resolver1", "Resolver2").joinToString(" > "), map["resolvers"])
        assertEquals("SomeErrorType", map["errorType"])
    }

    @Test
    fun testToString() {
        val metadata = ErrorMetadata(
            fieldName = "testField",
            parentType = "TestType",
            operationName = "TestOperation",
            isFrameworkError = true,
            resolvers = listOf("Resolver1", "Resolver2"),
            errorType = "SomeErrorType"
        )
        val str = metadata.toString()
        assertTrue(str.contains("testField"))
        assertTrue(str.contains("TestType"))
        assertTrue(str.contains("TestOperation"))
        assertTrue(str.contains("true"))
        assertTrue(str.contains("Resolver1"))
        assertTrue(str.contains("Resolver2"))
        assertTrue(str.contains("SomeErrorType"))
    }

    @Test
    fun `test custom error reporter implementation is invoked`() {
        var reportedError: Throwable? = null
        var reportedMessage: String? = null
        var reportedMetadata: ErrorMetadata? = null

        val reporter = ResolverErrorReporter { exception, _, _, errorMessage, metadata ->
            reportedError = exception
            reportedMessage = errorMessage
            reportedMetadata = metadata
        }

        val exception = RuntimeException("Test error")
        val fieldDef = createMockFieldDefinition()
        val env = createMockDataFetchingEnvironment()
        val metadata = ErrorMetadata(fieldName = "test", errorType = "TEST_ERROR")

        reporter.reportError(exception, fieldDef, env, "Error occurred", metadata)

        assertEquals(exception, reportedError)
        assertEquals("Error occurred", reportedMessage)
        assertEquals(metadata, reportedMetadata)
    }

    @Test
    fun `test NoOpResolverErrorReporter does not throw exceptions`() {
        val reporter = ResolverErrorReporter.NoOpResolverErrorReporter
        val exception = RuntimeException("Test error")
        val fieldDef = createMockFieldDefinition()
        val env = createMockDataFetchingEnvironment()
        val metadata = ErrorMetadata.EMPTY

        assertDoesNotThrow {
            reporter.reportError(exception, fieldDef, env, "Error message", metadata)
        }
    }

    @Test
    fun `test NoOpResolverErrorReporter is accessible`() {
        val reporter = ResolverErrorReporter.NoOpResolverErrorReporter
        assertNotNull(reporter)
    }

    @Test
    fun `test reporter with null exception message`() {
        var capturedMessage: String? = null

        val reporter = ResolverErrorReporter { exception, _, _, errorMessage, _ ->
            capturedMessage = "${exception.message}:$errorMessage"
        }

        val exception = RuntimeException() // null message
        val fieldDef = createMockFieldDefinition()
        val env = createMockDataFetchingEnvironment()

        reporter.reportError(exception, fieldDef, env, "Custom error message", ErrorMetadata.EMPTY)

        assertEquals("null:Custom error message", capturedMessage)
    }

    @Test
    fun `test reporter with empty metadata`() {
        var capturedMetadata: ErrorMetadata? = null

        val reporter = ResolverErrorReporter { _, _, _, _, metadata ->
            capturedMetadata = metadata
        }

        val exception = RuntimeException("Test")
        val fieldDef = createMockFieldDefinition()
        val env = createMockDataFetchingEnvironment()

        reporter.reportError(exception, fieldDef, env, "Error", ErrorMetadata.EMPTY)

        assertEquals(ErrorMetadata.EMPTY, capturedMetadata)
    }

    @Test
    fun `test reporter with fully populated metadata`() {
        var capturedMetadata: ErrorMetadata? = null

        val reporter = ResolverErrorReporter { _, _, _, _, metadata ->
            capturedMetadata = metadata
        }

        val metadata = ErrorMetadata(
            fieldName = "userName",
            parentType = "User",
            operationName = "GetUser",
            isFrameworkError = false,
            resolvers = listOf("UserResolver"),
            errorType = "NOT_FOUND",
            executionPath = listOf("user", "name"),
            sourceLocation = SourceLocation(line = 5, column = 10),
            source = mapOf("id" to "123"),
            context = "mock-context",
            localContext = "mock-local-context",
            componentName = "user-service"
        )

        val exception = RuntimeException("User not found")
        val fieldDef = createMockFieldDefinition()
        val env = createMockDataFetchingEnvironment()

        reporter.reportError(exception, fieldDef, env, "User not found error", metadata)

        assertEquals(metadata, capturedMetadata)
        assertEquals("userName", capturedMetadata?.fieldName)
        assertEquals("User", capturedMetadata?.parentType)
        assertEquals("NOT_FOUND", capturedMetadata?.errorType)
    }

    @Test
    fun `test reporter with nested exception`() {
        var capturedException: Throwable? = null

        val reporter = ResolverErrorReporter { exception, _, _, _, _ ->
            capturedException = exception
        }

        val cause = IllegalStateException("Root cause")
        val exception = RuntimeException("Wrapper exception", cause)
        val fieldDef = createMockFieldDefinition()
        val env = createMockDataFetchingEnvironment()

        reporter.reportError(exception, fieldDef, env, "Error", ErrorMetadata.EMPTY)

        assertEquals(exception, capturedException)
        assertEquals(cause, capturedException?.cause)
    }

    @Test
    fun `test reporter receives all parameters correctly`() {
        var receivedException: Throwable? = null
        var receivedFieldDef: GraphQLFieldDefinition? = null
        var receivedEnv: Any? = null
        var receivedMessage: String? = null
        var receivedMetadata: ErrorMetadata? = null

        val reporter = ResolverErrorReporter { exception, fieldDefinition, dataFetchingEnvironment, errorMessage, metadata ->
            receivedException = exception
            receivedFieldDef = fieldDefinition
            receivedEnv = dataFetchingEnvironment
            receivedMessage = errorMessage
            receivedMetadata = metadata
        }

        val exception = RuntimeException("Test")
        val fieldDef = createMockFieldDefinition()
        val env = createMockDataFetchingEnvironment()
        val metadata = ErrorMetadata(fieldName = "test")

        reporter.reportError(exception, fieldDef, env, "Error occurred", metadata)

        assertEquals(exception, receivedException)
        assertEquals(fieldDef, receivedFieldDef)
        assertEquals(env, receivedEnv)
        assertEquals("Error occurred", receivedMessage)
        assertEquals(metadata, receivedMetadata)
    }

    @Test
    fun `test multiple reporters can be created`() {
        var reporter1Called = false
        var reporter2Called = false

        val reporter1 = ResolverErrorReporter { _, _, _, _, _ ->
            reporter1Called = true
        }

        val reporter2 = ResolverErrorReporter { _, _, _, _, _ ->
            reporter2Called = true
        }

        val exception = RuntimeException("Test")
        val fieldDef = createMockFieldDefinition()
        val env = createMockDataFetchingEnvironment()

        reporter1.reportError(exception, fieldDef, env, "Error", ErrorMetadata.EMPTY)
        reporter2.reportError(exception, fieldDef, env, "Error", ErrorMetadata.EMPTY)

        assertTrue(reporter1Called)
        assertTrue(reporter2Called)
    }

    private fun createMockFieldDefinition(): GraphQLFieldDefinition {
        return GraphQLFieldDefinition.newFieldDefinition()
            .name("testField")
            .type(graphql.Scalars.GraphQLString)
            .dataFetcher(StaticDataFetcher("test"))
            .build()
    }

    private fun createMockDataFetchingEnvironment(): graphql.schema.DataFetchingEnvironment {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .build()
    }
}
