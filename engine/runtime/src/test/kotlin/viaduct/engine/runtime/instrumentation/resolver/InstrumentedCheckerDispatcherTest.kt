@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.instrumentation.resolver

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerMetadata
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime.CheckerDispatcher

internal class InstrumentedCheckerDispatcherTest {
    @Test
    fun `execute skips instrumentation when checkerMetadata is null`() =
        runBlocking {
            val mockDispatcher: CheckerDispatcher = mockk()
            val mockExecutor: CheckerExecutor = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val mockResult: CheckerResult = CheckerResult.Success

            every { mockDispatcher.executor } returns mockExecutor
            every { mockExecutor.requiredSelectionSets } returns emptyMap()
            every { mockExecutor.checkerMetadata } returns null
            coEvery { mockExecutor.execute(any(), any(), any(), any()) } returns mockResult

            val testClass = InstrumentedCheckerDispatcher(mockDispatcher, instrumentation)
            val result = testClass.executor.execute(emptyMap(), emptyMap(), mockk(), CheckerExecutor.CheckerType.FIELD)

            assertSame(mockResult, result)
            assertEquals(0, instrumentation.executeCheckerContexts.size)
        }

    @Test
    fun `execute calls instrumentation on success`() =
        runBlocking {
            val mockDispatcher: CheckerDispatcher = mockk()
            val mockExecutor: CheckerExecutor = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val mockMetadata = CheckerMetadata(checkerName = "Himeji", typeName = "User", fieldName = "email")
            val mockResult: CheckerResult = CheckerResult.Success

            every { mockDispatcher.executor } returns mockExecutor
            every { mockExecutor.requiredSelectionSets } returns emptyMap()
            every { mockExecutor.checkerMetadata } returns mockMetadata
            coEvery { mockExecutor.execute(any(), any(), any(), any()) } returns mockResult

            val testClass = InstrumentedCheckerDispatcher(mockDispatcher, instrumentation)
            val result = testClass.executor.execute(emptyMap(), emptyMap(), mockk(), CheckerExecutor.CheckerType.FIELD)

            assertSame(mockResult, result)
            assertEquals(1, instrumentation.executeCheckerContexts.size)
            val context = instrumentation.executeCheckerContexts.first()
            assertEquals(mockMetadata, context.parameters.checkerMetadata)
        }

    @Test
    fun `execute calls instrumentation on exception`() =
        runBlocking {
            val mockDispatcher: CheckerDispatcher = mockk()
            val mockExecutor: CheckerExecutor = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val mockMetadata = CheckerMetadata(checkerName = "Gandalf", typeName = "Query")
            val exception = RuntimeException("test error")

            every { mockDispatcher.executor } returns mockExecutor
            every { mockExecutor.requiredSelectionSets } returns emptyMap()
            every { mockExecutor.checkerMetadata } returns mockMetadata
            coEvery { mockExecutor.execute(any(), any(), any(), any()) } throws exception

            val testClass = InstrumentedCheckerDispatcher(mockDispatcher, instrumentation)

            val thrown = assertThrows<RuntimeException> {
                testClass.executor.execute(emptyMap(), emptyMap(), mockk(), CheckerExecutor.CheckerType.FIELD)
            }
            assertSame(exception, thrown)

            assertEquals(1, instrumentation.executeCheckerContexts.size)
            val context = instrumentation.executeCheckerContexts.first()
            assertEquals(mockMetadata, context.parameters.checkerMetadata)
        }
}
