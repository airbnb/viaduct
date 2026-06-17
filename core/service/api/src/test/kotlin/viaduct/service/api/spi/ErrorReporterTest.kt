package viaduct.service.api.spi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.SourceLocation

class ErrorReporterTest {
    @Test
    fun `test Metadata with all fields`() {
        val sourceLocation = SourceLocation(line = 10, column = 5)
        val metadata = ErrorReporter.Metadata(
            fieldName = "username",
            parentType = "User",
            operationName = "GetUser",
            isFrameworkError = false,
            resolvers = listOf("UserResolver", "ProfileResolver"),
            executionPath = listOf("user", "profile", 0),
            sourceLocation = sourceLocation,
            requestContext = "requestContextObject",
            componentName = "UserComponent"
        )

        assertEquals("username", metadata.fieldName)
        assertEquals("User", metadata.parentType)
        assertEquals("GetUser", metadata.operationName)
        assertEquals(false, metadata.isFrameworkError)
        assertEquals(listOf("UserResolver", "ProfileResolver"), metadata.resolvers)
        assertEquals(listOf("user", "profile", 0), metadata.executionPath)
        assertEquals(sourceLocation, metadata.sourceLocation)
        assertEquals("requestContextObject", metadata.requestContext)
        assertEquals("UserComponent", metadata.componentName)
    }

    @Test
    fun `test Metadata with default values`() {
        val metadata = ErrorReporter.Metadata()

        assertNull(metadata.fieldName)
        assertNull(metadata.parentType)
        assertNull(metadata.operationName)
        assertNull(metadata.isFrameworkError)
        assertNull(metadata.resolvers)
        assertNull(metadata.executionPath)
        assertNull(metadata.sourceLocation)
        assertNull(metadata.requestContext)
        assertNull(metadata.componentName)
    }

    @Test
    fun `test Metadata EMPTY companion object`() {
        val empty = ErrorReporter.Metadata.EMPTY

        assertNull(empty.fieldName)
        assertNull(empty.parentType)
        assertNull(empty.operationName)
        assertEquals(ErrorReporter.Metadata(), empty)
    }

    @Test
    fun `toExtensions includes graphql error fields`() {
        val metadata = ErrorReporter.Metadata(
            fieldName = "field1",
            parentType = "ParentType1",
            operationName = "Operation1",
            isFrameworkError = true,
            resolvers = listOf("Resolver1", "Resolver2")
        )

        val extensions = metadata.toExtensions()

        assertEquals("field1", extensions["fieldName"])
        assertEquals("ParentType1", extensions["parentType"])
        assertEquals("Operation1", extensions["operationName"])
        assertEquals("true", extensions["isFrameworkError"])
        assertEquals("Resolver1 > Resolver2", extensions["resolvers"])
    }

    @Test
    fun `toExtensions omits requestContext and executionPath`() {
        val metadata = ErrorReporter.Metadata(
            fieldName = "field1",
            requestContext = "ctx",
            executionPath = listOf("user", 0),
            sourceLocation = SourceLocation(1, 1)
        )

        val extensions = metadata.toExtensions()

        assertEquals("field1", extensions["fieldName"])
        assertNull(extensions["requestContext"])
        assertNull(extensions["executionPath"])
        assertNull(extensions["sourceLocation"])
    }

    @Test
    fun `toExtensions with partial fields`() {
        val metadata = ErrorReporter.Metadata(
            fieldName = "field1",
            parentType = "ParentType1"
        )

        val extensions = metadata.toExtensions()

        assertEquals("field1", extensions["fieldName"])
        assertEquals("ParentType1", extensions["parentType"])
        assertNull(extensions["operationName"])
        assertNull(extensions["isFrameworkError"])
        assertNull(extensions["resolvers"])
    }

    @Test
    fun `toExtensions with empty metadata`() {
        val metadata = ErrorReporter.Metadata()
        assertTrue(metadata.toExtensions().isEmpty())
    }

    @Test
    fun `test Metadata toString`() {
        val metadata = ErrorReporter.Metadata(
            fieldName = "field1",
            parentType = "Type1",
            operationName = "Op1"
        )

        val str = metadata.toString()
        assertTrue(str.contains("field1"))
        assertTrue(str.contains("Type1"))
        assertTrue(str.contains("Op1"))
    }

    @Test
    fun `test ErrorReporter NoOp implementation`() {
        val reporter = ErrorReporter.NOOP
        reporter.reportResolverError(
            exception = RuntimeException("test"),
            errorMessage = "test message",
            metadata = ErrorReporter.Metadata()
        )
    }

    @Test
    fun `test ErrorReporter functional interface`() {
        var capturedMessage = ""
        val reporter = ErrorReporter { _, message, _ ->
            capturedMessage = message
        }

        reporter.reportResolverError(
            exception = RuntimeException("error"),
            errorMessage = "captured message",
            metadata = ErrorReporter.Metadata()
        )

        assertEquals("captured message", capturedMessage)
    }
}
