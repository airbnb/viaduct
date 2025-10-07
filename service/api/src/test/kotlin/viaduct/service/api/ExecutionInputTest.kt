package viaduct.service.api

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class ExecutionInputTest {
    @Test
    fun testExecutionInput() {
        val executionInput = ExecutionInput.create("scope123", "query", variables = mapOf("userId" to 1))
        assertNotNull(executionInput.variables)
        assertEquals("scope123", executionInput.schemaId)
    }

    @Test
    fun `create generates required ids when not provided`() {
        val input = ExecutionInput.create("test-schema", "query { me }")

        assertEquals("test-schema", input.schemaId)
        assertEquals("query { me }", input.operationText)
        assertNotNull(input.operationId)
        assertNotNull(input.executionId)
    }

    @Test
    fun `builder requires schemaId and operationText`() {
        assertThrows<IllegalStateException> {
            ExecutionInput.builder()
                .schemaId("test-schema")
                .build()
        }
    }

    @Test
    fun `builder with all parameters`() {
        val input = ExecutionInput.builder()
            .schemaId("test-schema")
            .operationText("query GetUser { user { name } }")
            .operationName("GetUser")
            .variables(mapOf("id" to 123))
            .build()

        assertEquals("test-schema", input.schemaId)
        assertEquals("GetUser", input.operationName)
        assertEquals(mapOf("id" to 123), input.variables)
    }

    @Test
    fun `create with all fields`() {
        val variables = mapOf("userId" to 456, "limit" to 10)
        val context = "test-context"

        val input = ExecutionInput.create(
            schemaId = "full-schema",
            operationText = "query GetUsers(\$userId: ID!, \$limit: Int) { users(userId: \$userId, limit: \$limit) { name } }",
            operationName = "GetUsers",
            variables = variables,
            requestContext = context
        )

        assertEquals("full-schema", input.schemaId)
        assertEquals("query GetUsers(\$userId: ID!, \$limit: Int) { users(userId: \$userId, limit: \$limit) { name } }", input.operationText)
        assertEquals("GetUsers", input.operationName)
        assertEquals(variables, input.variables)
        assertEquals(context, input.requestContext)
        assertNotNull(input.operationId)
        assertNotNull(input.executionId)
    }
}
