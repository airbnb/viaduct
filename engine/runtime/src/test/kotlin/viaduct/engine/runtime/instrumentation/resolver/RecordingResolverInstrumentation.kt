package viaduct.engine.runtime.instrumentation.resolver

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation

class RecordingResolverInstrumentation : ViaductResolverInstrumentation {
    class RecordingInstrumentationState : ViaductResolverInstrumentation.InstrumentationState

    class RecordingOnCompleted : ViaductResolverInstrumentation.OnCompleted {
        val onCompletedCalled = AtomicBoolean(false)
        var completedResult: Any? = null
        var completedException: Throwable? = null

        override fun onCompleted(
            result: Any?,
            error: Throwable?
        ) {
            completedResult = result
            completedException = error
            onCompletedCalled.set(true)
        }
    }

    class RecordingFetchSelectionContext(
        val parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
        val onCompleted: RecordingOnCompleted
    )

    class RecordingExecuteResolverContext(
        val parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
        val onCompleted: RecordingOnCompleted
    )

    val fetchSelectionContexts = ConcurrentLinkedQueue<RecordingFetchSelectionContext>()
    val executeResolverContexts = ConcurrentLinkedQueue<RecordingExecuteResolverContext>()

    override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters): ViaductResolverInstrumentation.InstrumentationState {
        return RecordingInstrumentationState()
    }

    override fun beginExecuteResolver(
        parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?
    ): ViaductResolverInstrumentation.OnCompleted {
        val onCompleted = RecordingOnCompleted()
        val context = RecordingExecuteResolverContext(parameters, onCompleted)
        executeResolverContexts.add(context)
        return onCompleted
    }

    override fun beginFetchSelection(
        parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?
    ): ViaductResolverInstrumentation.OnCompleted {
        val onCompleted = RecordingOnCompleted()
        val context = RecordingFetchSelectionContext(parameters, onCompleted)
        fetchSelectionContexts.add(context)
        return onCompleted
    }

    fun reset() {
        fetchSelectionContexts.clear()
        executeResolverContexts.clear()
    }
}
