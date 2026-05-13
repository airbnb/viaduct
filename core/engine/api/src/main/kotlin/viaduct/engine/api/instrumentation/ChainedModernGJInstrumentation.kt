package viaduct.engine.api.instrumentation

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.schema.DataFetchingEnvironment
import viaduct.engine.api.spi.CheckerExecutor

/**
 * Composite [ViaductModernGJInstrumentation] that delegates each lifecycle hook to every
 * instrumentation in [gjInstrumentations] in order.
 *
 * For hooks that return a single value (e.g. [instrumentAccessCheck]) the result of each
 * delegate is threaded as the input to the next, producing a composed result. For hooks that
 * return an [graphql.execution.instrumentation.InstrumentationContext], each delegate's context
 * is collected into a [ChainedInstrumentationContext] that dispatches to all of them.
 */
open class ChainedModernGJInstrumentation(
    val gjInstrumentations: List<ViaductModernGJInstrumentation>
) : ChainedInstrumentation(gjInstrumentations), ViaductModernGJInstrumentation {
    override fun beginFetchObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Unit> =
        ChainedInstrumentationContext(
            gjInstrumentations.map { instr ->
                instr.beginFetchObject(parameters, getState(instr, state))
            }
        )

    override fun beginCompleteObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any> =
        ChainedInstrumentationContext(
            gjInstrumentations.map { instr ->
                instr.beginCompleteObject(parameters, getState(instr, state))
            }
        )

    override fun instrumentAccessCheck(
        checkerExecutor: CheckerExecutor,
        dataFetchingEnvironment: DataFetchingEnvironment,
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): CheckerExecutor {
        var instrumentedChecker = checkerExecutor
        for (instr in gjInstrumentations) {
            instrumentedChecker = instr.instrumentAccessCheck(instrumentedChecker, dataFetchingEnvironment, parameters, getState(instr, state))
        }

        return instrumentedChecker
    }

    override fun beginNodeFetching(
        parameters: InstrumentNodeFetchingParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        ChainedInstrumentationContext(
            gjInstrumentations.mapNotNull { instr ->
                instr.beginNodeFetching(parameters, getState(instr, state))
            }
        )
}
