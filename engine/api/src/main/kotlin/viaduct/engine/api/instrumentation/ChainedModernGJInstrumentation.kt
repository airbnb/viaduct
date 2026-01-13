package viaduct.engine.api.instrumentation

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import viaduct.engine.api.CheckerExecutor

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
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): CheckerExecutor {
        var instrumentedChecker = checkerExecutor
        for (instr in gjInstrumentations) {
            instrumentedChecker = instr.instrumentAccessCheck(instrumentedChecker, parameters, getState(instr, state))
        }

        return instrumentedChecker
    }
}
