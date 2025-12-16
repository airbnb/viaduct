package viaduct.service.api.spi

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ErrorMetadataTest {
    @Test
    fun `test error metadata with all original fields populated`() {
        val metadata = ErrorMetadata(
            fieldName = "name",
            parentType = "User",
            operationName = "GetUser",
            isFrameworkError = false,
            resolvers = listOf("UserResolver", "NameResolver"),
            errorType = "USER_ERROR"
        )

        assertEquals("name", metadata.fieldName)
        assertEquals("User", metadata.parentType)
        assertEquals("GetUser", metadata.operationName)
        assertEquals(false, metadata.isFrameworkError)
        assertEquals(listOf("UserResolver", "NameResolver"), metadata.resolvers)
        assertEquals("USER_ERROR", metadata.errorType)
    }

    @Test
    fun `test error metadata with new DFE fields populated`() {
        val sourceLocation = SourceLocation(line = 5, column = 10, sourceName = "query.graphql")
        val metadata = ErrorMetadata(
            fieldName = "name",
            parentType = "User",
            operationName = "GetUser",
            executionPath = listOf("user", "profile", "name"),
            sourceLocation = sourceLocation,
            source = mapOf("id" to "123"),
            context = "mock-context",
            localContext = "mock-local-context",
            componentName = "user-service"
        )

        assertEquals(listOf("user", "profile", "name"), metadata.executionPath)
        assertNotNull(metadata.sourceLocation)
        assertEquals(5, metadata.sourceLocation?.line)
        assertEquals(10, metadata.sourceLocation?.column)
        assertEquals("query.graphql", metadata.sourceLocation?.sourceName)
        assertNotNull(metadata.source)
        assertEquals("mock-context", metadata.context)
        assertEquals("mock-local-context", metadata.localContext)
        assertEquals("user-service", metadata.componentName)
    }

    @Test
    fun `test error metadata EMPTY constant`() {
        val metadata = ErrorMetadata.EMPTY

        assertNull(metadata.fieldName)
        assertNull(metadata.parentType)
        assertNull(metadata.operationName)
        assertNull(metadata.isFrameworkError)
        assertNull(metadata.resolvers)
        assertNull(metadata.errorType)
        assertNull(metadata.executionPath)
        assertNull(metadata.sourceLocation)
        assertNull(metadata.source)
        assertNull(metadata.context)
        assertNull(metadata.localContext)
        assertNull(metadata.componentName)
    }

    @Test
    fun `test error metadata toMap for backward compatibility`() {
        val metadata = ErrorMetadata(
            fieldName = "name",
            parentType = "User",
            operationName = "GetUser",
            isFrameworkError = true,
            resolvers = listOf("Resolver1", "Resolver2"),
            errorType = "VALIDATION_ERROR",
            executionPath = listOf("user", "name"),
            context = "context-value",
            componentName = "my-component"
        )

        val map = metadata.toMap()

        // Only original fields included in map
        assertEquals("name", map["fieldName"])
        assertEquals("User", map["parentType"])
        assertEquals("GetUser", map["operationName"])
        assertEquals("true", map["isFrameworkError"])
        assertEquals("Resolver1 > Resolver2", map["resolvers"])
        assertEquals("VALIDATION_ERROR", map["errorType"])

        // New fields NOT included in toMap() for backward compatibility
        assertFalse(map.containsKey("executionPath"))
        assertFalse(map.containsKey("context"))
        assertFalse(map.containsKey("componentName"))
    }

    @Test
    fun `test error metadata toString excludes new fields`() {
        val metadata = ErrorMetadata(
            fieldName = "testField",
            parentType = "TestType",
            operationName = "TestOp",
            isFrameworkError = false,
            resolvers = listOf("R1"),
            errorType = "ERROR"
        )

        val str = metadata.toString()

        assertTrue(str.contains("testField"))
        assertTrue(str.contains("TestType"))
        assertTrue(str.contains("TestOp"))
        assertTrue(str.contains("false"))
        assertTrue(str.contains("R1"))
        assertTrue(str.contains("ERROR"))
    }

    @Test
    fun `test error metadata with null contexts handles gracefully`() {
        val metadata = ErrorMetadata(
            fieldName = "test",
            parentType = "Test",
            operationName = null,
            context = null,
            localContext = null
        )

        assertNull(metadata.context)
        assertNull(metadata.localContext)
    }
}
