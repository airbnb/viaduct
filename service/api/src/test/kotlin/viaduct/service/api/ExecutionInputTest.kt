package viaduct.service.api

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class ExecutionInputTest {
    @Test
    fun testExecutionInput() {
        val executionInput = ExecutionInput.create("query", variables = mapOf("userId" to 1))
        assertNotNull(executionInput.variables)
    }

    @Test
    fun `create generates required ids when not provided`() {
        val input = ExecutionInput.create("query { me }")

        assertEquals("query { me }", input.operationText)
        assertNotNull(input.operationId)
        assertNotNull(input.executionId)
    }

    @Test
    fun `builder requires operationText`() {
        assertThrows<IllegalStateException> {
            ExecutionInput
                .builder()
                .build()
        }
    }

    @Test
    fun `builder with all parameters`() {
        val input = ExecutionInput
            .builder()
            .operationText("query GetUser { user { name } }")
            .operationName("GetUser")
            .variables(mapOf("id" to 123))
            .build()

        assertEquals("GetUser", input.operationName)
        assertEquals(mapOf("id" to 123), input.variables)
    }

    @Test
    fun `create with all fields`() {
        val variables = mapOf("userId" to 456, "limit" to 10)
        val context = "test-context"

        val input = ExecutionInput.create(
            operationText = "query GetUsers(\$userId: ID!, \$limit: Int) { users(userId: \$userId, limit: \$limit) { name } }",
            operationName = "GetUsers",
            variables = variables,
            requestContext = context
        )

        assertEquals("query GetUsers(\$userId: ID!, \$limit: Int) { users(userId: \$userId, limit: \$limit) { name } }", input.operationText)
        assertEquals("GetUsers", input.operationName)
        assertEquals(variables, input.variables)
        assertEquals(context, input.requestContext)
        assertNotNull(input.operationId)
        assertNotNull(input.executionId)
    }
}
