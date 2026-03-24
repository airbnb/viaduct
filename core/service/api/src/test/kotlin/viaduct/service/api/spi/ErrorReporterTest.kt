package viaduct.service.api.spi

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
            source = "sourceObject",
            context = "contextObject",
            localContext = "localContextObject",
            componentName = "UserComponent"
        )

        assertEquals("username", metadata.fieldName)
        assertEquals("User", metadata.parentType)
        assertEquals("GetUser", metadata.operationName)
        assertEquals(false, metadata.isFrameworkError)
        assertEquals(listOf("UserResolver", "ProfileResolver"), metadata.resolvers)
        assertEquals(listOf("user", "profile", 0), metadata.executionPath)
        assertEquals(sourceLocation, metadata.sourceLocation)
        assertEquals("sourceObject", metadata.source)
        assertEquals("contextObject", metadata.context)
        assertEquals("localContextObject", metadata.localContext)
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
        assertNull(metadata.source)
        assertNull(metadata.context)
        assertNull(metadata.localContext)
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
    fun `test Metadata toMap with all fields`() {
        val metadata = ErrorReporter.Metadata(
            fieldName = "field1",
            parentType = "ParentType1",
            operationName = "Operation1",
            isFrameworkError = true,
            resolvers = listOf("Resolver1", "Resolver2")
        )

        val map = metadata.toMap()

        assertEquals("field1", map["fieldName"])
        assertEquals("ParentType1", map["parentType"])
        assertEquals("Operation1", map["operationName"])
        assertEquals("true", map["isFrameworkError"])
        assertEquals("Resolver1 > Resolver2", map["resolvers"])
    }

    @Test
    fun `test Metadata toMap with partial fields`() {
        val metadata = ErrorReporter.Metadata(
            fieldName = "field1",
            parentType = "ParentType1"
        )

        val map = metadata.toMap()

        assertEquals("field1", map["fieldName"])
        assertEquals("ParentType1", map["parentType"])
        assertNull(map["operationName"])
        assertNull(map["isFrameworkError"])
        assertNull(map["resolvers"])
    }

    @Test
    fun `test Metadata toMap with empty metadata`() {
        val metadata = ErrorReporter.Metadata()
        val map = metadata.toMap()
        assertTrue(map.isEmpty())
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

    @Test
    fun `test Metadata with deprecated dataFetchingEnvironment`() {
        @Suppress("DEPRECATION")
        val metadata = ErrorReporter.Metadata(
            fieldName = "testField",
            dataFetchingEnvironment = null
        )

        assertEquals("testField", metadata.fieldName)
        @Suppress("DEPRECATION")
        assertNull(metadata.dataFetchingEnvironment)
    }
}
