@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.instrumentation.resolver

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.mocks.ContextMocks

@OptIn(ExperimentalCoroutinesApi::class)
internal class InstrumentedEngineExecutionContextTest {
    private fun defaultImpl(): EngineExecutionContextImpl = ContextMocks().engineExecutionContextImpl

    @Test
    fun `resolveSelectionSet wraps returned EngineObjectData Sync in InstrumentedEngineObjectData Sync`() =
        runBlocking {
            // Given
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(mockk())
            val rawObjectData: EngineObjectData.Sync = mockk()
            val selectionSet: EngineSelectionSet = mockk()
            val impl: EngineExecutionContextImpl = mockk(relaxed = true)

            coEvery { impl.resolveSelectionSet(selectionSet, any()) } returns rawObjectData

            val testClass = InstrumentedEngineExecutionContext(impl, instrumentation, state)

            // When
            val result = testClass.resolveSelectionSet(selectionSet, ResolveSelectionSetOptions.DEFAULT)

            // Then — result is wrapped so get callbacks fire on subsequent field access
            assertInstanceOf(InstrumentedEngineObjectData.Sync::class.java, result)
            assertSame(rawObjectData, (result as InstrumentedEngineObjectData.Sync).engineObjectData)
        }

    @Test
    fun `resolveSelectionSet passes the same instrumentation and state to the wrapper`() =
        runBlocking {
            // Given
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(mockk())
            val rawObjectData: EngineObjectData.Sync = mockk()
            val selectionSet: EngineSelectionSet = mockk()
            val impl: EngineExecutionContextImpl = mockk(relaxed = true)

            coEvery { impl.resolveSelectionSet(selectionSet, any()) } returns rawObjectData

            val testClass = InstrumentedEngineExecutionContext(impl, instrumentation, state)

            // When
            val result = testClass.resolveSelectionSet(selectionSet, ResolveSelectionSetOptions.DEFAULT)
                as InstrumentedEngineObjectData.Sync

            // Then — the same instrumentation and state are threaded through to the wrapper so
            // that get callbacks on the returned object are associated with this resolver's
            // instrumentation context.
            assertSame(instrumentation, result.resolverInstrumentation)
            assertSame(state, result.instrumentationState)
        }

    @Test
    fun `resolveSelectionSet forwards selectionSet and options to impl`() =
        runBlocking {
            // Given
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(mockk())
            val rawObjectData: EngineObjectData.Sync = mockk()
            val selectionSet: EngineSelectionSet = mockk()
            val options = ResolveSelectionSetOptions.MUTATION
            val impl: EngineExecutionContextImpl = mockk(relaxed = true)

            coEvery { impl.resolveSelectionSet(selectionSet, options) } returns rawObjectData

            val testClass = InstrumentedEngineExecutionContext(impl, instrumentation, state)

            // When
            testClass.resolveSelectionSet(selectionSet, options)

            // Then — impl is invoked with exactly the selectionSet and options passed by the caller
            coVerify(exactly = 1) { impl.resolveSelectionSet(selectionSet, options) }
        }

    @Test
    fun `impl property exposes the underlying EngineExecutionContextImpl`() {
        // Given
        val impl = defaultImpl()
        val instrumentation = RecordingResolverInstrumentation()
        val state = instrumentation.createInstrumentationState(mockk())

        val testClass = InstrumentedEngineExecutionContext(impl, instrumentation, state)

        // Then — engine-internal code can recover the concrete impl without a cast to the
        // decorator class, satisfying the InternalEngineExecutionContext contract.
        assertSame(impl, testClass.impl)
    }
}
