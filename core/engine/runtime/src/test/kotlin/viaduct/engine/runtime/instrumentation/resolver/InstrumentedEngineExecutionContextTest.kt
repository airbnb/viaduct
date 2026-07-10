@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.instrumentation.resolver

import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
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

            coEvery { impl.resolveSelectionSet(selectionSet, any(), any()) } returns rawObjectData

            val testClass = InstrumentedEngineExecutionContext(impl, instrumentation, state)

            // When
            val result = testClass.resolveSelectionSet(selectionSet, ResolveSelectionSetOptions.DEFAULT)

            // Then — result is wrapped so get callbacks fire on subsequent field access
            result.shouldBeInstanceOf<InstrumentedEngineObjectData.Sync>()
            assertSame(rawObjectData, result.engineObjectData)
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

            coEvery { impl.resolveSelectionSet(selectionSet, any(), any()) } returns rawObjectData

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
    fun `resolveSelectionSet forwards selectionSet, options, and instrumentation context to impl`() =
        runBlocking {
            // Given
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(mockk())
            val rawObjectData: EngineObjectData.Sync = mockk()
            val selectionSet: EngineSelectionSet = mockk()
            val options = ResolveSelectionSetOptions.MUTATION
            val impl: EngineExecutionContextImpl = mockk(relaxed = true)

            val contextSlot = slot<ResolverInstrumentationContext>()
            coEvery {
                impl.resolveSelectionSet(selectionSet, options, capture(contextSlot))
            } returns rawObjectData

            val testClass = InstrumentedEngineExecutionContext(impl, instrumentation, state)

            // When
            testClass.resolveSelectionSet(selectionSet, options)

            // Then — impl is invoked with exactly the selectionSet and options passed by the caller,
            // plus a ResolverInstrumentationContext carrying this wrapper's instrumentation and state.
            coVerify(exactly = 1) { impl.resolveSelectionSet(selectionSet, options, any()) }
            assertSame(instrumentation, contextSlot.captured.instrumentation)
            assertSame(state, contextSlot.captured.state)
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
