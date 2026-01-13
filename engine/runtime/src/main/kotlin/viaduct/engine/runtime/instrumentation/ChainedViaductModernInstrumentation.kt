package viaduct.engine.runtime.instrumentation

import graphql.execution.instrumentation.FieldFetchingInstrumentationContext
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.schema.DataFetcher
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.instrumentation.ChainedInstrumentation
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.api.instrumentation.ViaductModernInstrumentation

class ChainedViaductModernInstrumentation private constructor(
    val modernInstrumentations: List<ViaductModernInstrumentation>,
    gjInstrumentations: List<ViaductModernGJInstrumentation>,
) : ChainedInstrumentation(gjInstrumentations), ViaductModernGJInstrumentation {
    constructor(modernInstrumentations: List<ViaductModernInstrumentation>) :
        this(modernInstrumentations, modernInstrumentations.map { it.asGJInstrumentation() })

    private val linkedInstrumentations = linkedMapOf(*modernInstrumentations.zip(gjInstrumentations).toTypedArray())

    private val beginFetchObjectInstrumentations by lazy {
        mapInstrumentations<ViaductModernInstrumentation.WithBeginFetchObject>()
    }

    override fun beginFetchObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Unit> {
        return ChainedInstrumentationContext(
            beginFetchObjectInstrumentations.map { instr ->
                instr.beginFetchObject(parameters, getState(instr, state))
            }
        )
    }

    private val beginFieldExecutionInstrumentations by lazy {
        mapInstrumentations<ViaductModernInstrumentation.WithBeginFieldExecution>()
    }

    override fun beginFieldExecution(
        parameters: InstrumentationFieldParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any> {
        return ChainedInstrumentationContext(
            beginFieldExecutionInstrumentations.map { instr ->
                instr.beginFieldExecution(parameters, getState(instr, state))
            }
        )
    }

    private val beginFieldFetchingInstrumentations by lazy {
        mapInstrumentations<ViaductModernInstrumentation.WithBeginFieldFetching>()
    }

    override fun beginFieldFetching(
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): FieldFetchingInstrumentationContext? {
        return ChainedFieldFetchingInstrumentationContext(
            beginFieldFetchingInstrumentations.mapNotNull { instr ->
                instr.beginFieldFetching(parameters, getState(instr, state))
            }
        )
    }

    private val beginCompleteObjectInstrumentations by lazy {
        mapInstrumentations<ViaductModernInstrumentation.WithBeginCompleteObject>()
    }

    override fun beginCompleteObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any> {
        return ChainedInstrumentationContext(
            beginCompleteObjectInstrumentations.map { instr ->
                instr.beginCompleteObject(parameters, getState(instr, state))
            }
        )
    }

    private val beginFieldCompletionInstrumentations by lazy {
        mapInstrumentations<ViaductModernInstrumentation.WithBeginFieldCompletion>()
    }

    override fun beginFieldCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? {
        return ChainedInstrumentationContext(
            beginFieldCompletionInstrumentations.map { instr ->
                instr.beginFieldCompletion(parameters, getState(instr, state))
            }
        )
    }

    private val beginFieldListCompletionInstrumentations by lazy {
        mapInstrumentations<ViaductModernInstrumentation.WithBeginFieldListCompletion>()
    }

    override fun beginFieldListCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? {
        return ChainedInstrumentationContext(
            beginFieldListCompletionInstrumentations.map { instr ->
                instr.beginFieldListCompletion(parameters, getState(instr, state))
            }
        )
    }

    private val instrumentDataFetcherInstrumentations by lazy {
        mapInstrumentations<ViaductModernInstrumentation.WithInstrumentDataFetcher>()
    }

    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): DataFetcher<*> {
        return instrumentDataFetcherInstrumentations.fold(dataFetcher) { df, instr ->
            instr.instrumentDataFetcher(df, parameters, getState(instr, state))
        }
    }

    private val instrumentAccessCheckInstrumentations by lazy {
        mapInstrumentations<ViaductModernInstrumentation.WithInstrumentAccessCheck>()
    }

    override fun instrumentAccessCheck(
        checkerDispatcher: CheckerExecutor,
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): CheckerExecutor {
        return instrumentAccessCheckInstrumentations.fold(checkerDispatcher) { dispatcher, instr ->
            instr.instrumentAccessCheck(dispatcher, parameters, getState(instr, state))
        }
    }

    private inline fun <reified T : ViaductModernInstrumentation> mapInstrumentations() =
        linkedInstrumentations
            .filter { it.key is T }
            .values
}
