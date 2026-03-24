package viaduct.service.runtime

import graphql.ExecutionInput
import graphql.execution.preparsed.PreparsedDocumentEntry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.function.Function
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CachingPreparsedDocumentProviderTest {
    private lateinit var provider: CachingPreparsedDocumentProvider
    private lateinit var mockComputeFunction: Function<ExecutionInput, PreparsedDocumentEntry>
    private lateinit var mockEntry: PreparsedDocumentEntry

    @BeforeEach
    fun setUp() {
        provider = CachingPreparsedDocumentProvider()
        mockComputeFunction = mockk()
        mockEntry = mockk()
    }

    @Test
    fun `should cache and return same result for identical queries`() {
        val query = "query { user { id } }"
        val input1 = ExecutionInput.newExecutionInput().query(query).build()
        val input2 = ExecutionInput.newExecutionInput().query(query).build()
        every { mockComputeFunction.apply(any()) } returns mockEntry

        val result1 = provider.getDocumentAsync(input1, mockComputeFunction)
        val result2 = provider.getDocumentAsync(input2, mockComputeFunction)

        assertEquals(mockEntry, result1.get())
        assertEquals(mockEntry, result2.get())
        assertTrue(result1.isDone && result2.isDone)
        verify(exactly = 1) { mockComputeFunction.apply(any()) } // Only called once due to caching
    }

    @Test
    fun `should cache different queries separately`() {
        val query1 = "query { user { id } }"
        val query2 = "query { user { name } }"
        val input1 = ExecutionInput.newExecutionInput().query(query1).build()
        val input2 = ExecutionInput.newExecutionInput().query(query2).build()
        every { mockComputeFunction.apply(any()) } returns mockEntry

        provider.getDocumentAsync(input1, mockComputeFunction)
        provider.getDocumentAsync(input2, mockComputeFunction)

        verify(exactly = 2) { mockComputeFunction.apply(any()) } // Called once per unique query
    }
}
