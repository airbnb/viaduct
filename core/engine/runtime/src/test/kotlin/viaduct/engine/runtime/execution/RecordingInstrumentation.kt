package viaduct.engine.runtime.execution

import graphql.execution.instrumentation.FieldFetchingInstrumentationContext
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import viaduct.engine.api.instrumentation.ViaductModernInstrumentation

class RecordingInstrumentation :
    ViaductModernInstrumentation,
    ViaductModernInstrumentation.WithBeginFetchObject,
    ViaductModernInstrumentation.WithBeginFieldExecution,
    ViaductModernInstrumentation.WithBeginFieldFetching,
    ViaductModernInstrumentation.WithBeginCompleteObject,
    ViaductModernInstrumentation.WithBeginFieldCompletion,
    ViaductModernInstrumentation.WithBeginFieldListCompletion,
    ViaductModernInstrumentation.WithInstrumentDataFetcher {
    // Base class for recording contexts
    open class RecordingInstrumentationContext<T>(
        val parameters: Any
    ) : InstrumentationContext<T> {
        val onDispatchedCalled = AtomicBoolean(false)
        val onCompletedCalled = AtomicBoolean(false)
        var completedValue: T? = null
        var completedException: Throwable? = null

        override fun onDispatched() {
            onDispatchedCalled.set(true)
        }

        override fun onCompleted(
            result: T?,
            t: Throwable?
        ) {
            onCompletedCalled.set(true)
            completedValue = result
            completedException = t
        }
    }

    // For FieldFetchingInstrumentationContext, which might have additional methods
    class RecordingFieldFetchingInstrumentationContext(
        parameters: Any
    ) : RecordingInstrumentationContext<Any>(parameters), FieldFetchingInstrumentationContext

    // Recording storage
    val fetchObjectContexts = ConcurrentLinkedQueue<RecordingInstrumentationContext<Unit>>()
    val fieldExecutionContexts = ConcurrentLinkedQueue<RecordingInstrumentationContext<Any>>()
    val fieldFetchingContexts = ConcurrentLinkedQueue<RecordingFieldFetchingInstrumentationContext>()
    val completeObjectContexts = ConcurrentLinkedQueue<RecordingInstrumentationContext<Any>>()
    val fieldCompletionContexts = ConcurrentLinkedQueue<RecordingInstrumentationContext<Any>>()
    val fieldListCompletionContexts = ConcurrentLinkedQueue<RecordingInstrumentationContext<Any>>()
    val dataFetchingEnvironments = ConcurrentLinkedQueue<DataFetchingEnvironment>()

    fun reset() {
        fetchObjectContexts.clear()
        fieldExecutionContexts.clear()
        fieldFetchingContexts.clear()
        completeObjectContexts.clear()
        fieldCompletionContexts.clear()
        dataFetchingEnvironments.clear()
    }

    // Overriding the instrumentation methods
    override fun beginFetchObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Unit> {
        val context = RecordingInstrumentationContext<Unit>(parameters)
        fetchObjectContexts.add(context)
        return context
    }

    override fun beginFieldExecution(
        parameters: InstrumentationFieldParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any> {
        val context = RecordingInstrumentationContext<Any>(parameters)
        fieldExecutionContexts.add(context)
        return context
    }

    override fun beginFieldFetching(
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): FieldFetchingInstrumentationContext? {
        val context = RecordingFieldFetchingInstrumentationContext(parameters)
        fieldFetchingContexts.add(context)
        return context
    }

    override fun beginCompleteObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any> {
        val context = RecordingInstrumentationContext<Any>(parameters)
        completeObjectContexts.add(context)
        return context
    }

    override fun beginFieldCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? {
        val context = RecordingInstrumentationContext<Any>(parameters)
        fieldCompletionContexts.add(context)
        return context
    }

    override fun beginFieldListCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? {
        val context = RecordingInstrumentationContext<Any>(parameters)
        fieldListCompletionContexts.add(context)
        return context
    }

    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): DataFetcher<*> =
        DataFetcher { env ->
            dataFetchingEnvironments.add(env)
            dataFetcher.get(env)
        }
}
