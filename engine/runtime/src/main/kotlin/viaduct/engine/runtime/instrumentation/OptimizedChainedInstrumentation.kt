package viaduct.engine.runtime.instrumentation

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.schema.DataFetcher
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.instrumentation.ChainedModernGJInstrumentation
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.engine.api.instrumentation.asStandardInstrumentations

class OptimizedChainedInstrumentation(
    viaductInstrumentations: List<ViaductInstrumentationBase>
) : ChainedModernGJInstrumentation(viaductInstrumentations.asStandardInstrumentations()) {
    val linkedInstrumentations = linkedMapOf(*viaductInstrumentations.zip(gjInstrumentations).toTypedArray())

    private val beginFieldInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithBeginFieldExecution>()
    }

    override fun beginFieldExecution(
        parameters: InstrumentationFieldParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        ChainedInstrumentationContext(
            beginFieldInstrumentations.map { instr ->
                instr.beginFieldExecution(parameters, getState(instr, state))
            }
        )

    private val beginFieldFetchInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithBeginFieldFetch>()
    }

    @Suppress("DEPRECATION")
    @Deprecated("deprecated")
    override fun beginFieldFetch(
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any> =
        ChainedInstrumentationContext(
            beginFieldFetchInstrumentations.map { instr ->
                instr.beginFieldFetch(parameters, getState(instr, state))
            }
        )

    private val beginFieldCompleteInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithBeginFieldCompletion>()
    }

    override fun beginFieldCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        ChainedInstrumentationContext(
            beginFieldCompleteInstrumentations.map { instr ->
                instr.beginFieldCompletion(parameters, getState(instr, state))
            }
        )

    private val beginFieldListCompleteInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithBeginFieldListCompletion>()
    }

    override fun beginFieldListCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        ChainedInstrumentationContext(
            beginFieldListCompleteInstrumentations.map { instr ->
                instr.beginFieldListCompletion(parameters, getState(instr, state))
            }
        )

    private val instrumentDataFetcherInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithInstrumentDataFetcher>()
    }

    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): DataFetcher<*> {
        var instrumentedDataFetcher = dataFetcher
        for (instr in instrumentDataFetcherInstrumentations) {
            instrumentedDataFetcher =
                instr.instrumentDataFetcher(
                    instrumentedDataFetcher,
                    parameters,
                    getState(instr, state)
                )
        }
        return instrumentedDataFetcher
    }

    private val beginFetchObjectInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithBeginFetchObject>()
    }

    override fun beginFetchObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Unit> =
        ChainedInstrumentationContext(
            beginFetchObjectInstrumentations.map { instr ->
                instr.beginFetchObject(parameters, getState(instr, state))
            }
        )

    private val beginCompleteObjectInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithBeginCompleteObject>()
    }

    override fun beginCompleteObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any> =
        ChainedInstrumentationContext(
            beginCompleteObjectInstrumentations.map { instr ->
                instr.beginCompleteObject(parameters, getState(instr, state))
            }
        )

    private val instrumentAccessCheckInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithInstrumentAccessCheck>()
    }

    override fun instrumentAccessCheck(
        checkerExecutor: CheckerExecutor,
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): CheckerExecutor {
        var instrumentedChecker = checkerExecutor
        for (instr in instrumentAccessCheckInstrumentations) {
            instrumentedChecker = instr.instrumentAccessCheck(instrumentedChecker, parameters, getState(instr, state))
        }

        return instrumentedChecker
    }

    private inline fun <reified T : IViaductInstrumentation> mapInstrumentations() =
        linkedInstrumentations
            .filter { it.key is T }
            .values
}
