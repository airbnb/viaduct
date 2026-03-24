@file:Suppress("ForbiddenImport")

package viaduct.service.runtime

import graphql.ExecutionResult as GJExecutionResult
import graphql.GraphQLError as GJGraphQLError
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ViaductExecutionResultImplTest {
    @Test
    fun `ExecutionResultImpl returns data from underlying result`() {
        val mockResult = mockk<GJExecutionResult>()
        every { mockResult.getData<Map<String, Any?>>() } returns mapOf("field" to "value")

        val result = ExecutionResultImpl(mockResult)

        assertEquals(mapOf("field" to "value"), result.getData())
    }

    @Test
    fun `ExecutionResultImpl wraps errors from underlying result`() {
        val mockResult = mockk<GJExecutionResult>()
        val mockError = mockk<GJGraphQLError>()
        every { mockError.message } returns "Test error"
        every { mockError.path } returns null
        every { mockError.locations } returns null
        every { mockError.extensions } returns null
        every { mockResult.errors } returns listOf(mockError)

        val result = ExecutionResultImpl(mockResult)

        assertEquals(1, result.errors.size)
        assertEquals("Test error", result.errors[0].message)
    }

    @Test
    fun `ExecutionResultImpl returns extensions from underlying result`() {
        val mockResult = mockk<GJExecutionResult>()
        val extensions: Map<Any, Any?> = mapOf("tracing" to "data")
        every { mockResult.extensions } returns extensions

        val result = ExecutionResultImpl(mockResult)

        assertEquals(extensions, result.extensions)
    }

    @Test
    fun `ExecutionResultImpl delegates toSpecification to underlying result`() {
        val mockResult = mockk<GJExecutionResult>()
        val spec = mapOf("data" to mapOf("field" to "value"))
        every { mockResult.toSpecification() } returns spec

        val result = ExecutionResultImpl(mockResult)

        assertEquals(spec, result.toSpecification())
    }

    @Test
    fun `toExecutionResult extension creates ExecutionResultImpl`() {
        val mockResult = mockk<GJExecutionResult>()
        every { mockResult.getData<Any?>() } returns null
        every { mockResult.errors } returns emptyList()
        every { mockResult.extensions } returns null

        val result = mockResult.toExecutionResult()

        assertEquals(ExecutionResultImpl::class, result::class)
    }
}
