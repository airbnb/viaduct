package viaduct.engine.runtime.instrumentation

import graphql.execution.instrumentation.FieldFetchingInstrumentationContext
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext.noOp
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.instrumentation.ChainedModernGJInstrumentation
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.InstrumentNodeFetchingParameters
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.api.instrumentation.asStandardInstrumentations
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.ShadowFieldExecutionComparison

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
        chainContexts(beginFieldInstrumentations) { instr ->
            instr.beginFieldExecution(parameters, getState(instr, state))
        }

    private val shadowFieldExecutionInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithShadowFieldExecution>()
    }

    @InternalApi
    override fun requestShadowFieldExecution(
        parameters: InstrumentationFieldParameters,
        state: InstrumentationState?,
    ): ShadowFieldExecutionComparison? {
        val instrumentations = shadowFieldExecutionInstrumentations
        if (instrumentations.size == 1) {
            val instrumentation = instrumentations.first()
            return instrumentation.requestShadowFieldExecution(parameters, getState(instrumentation, state))
        }
        return ShadowFieldExecutionComparison.combine(
            instrumentations.mapNotNull { instrumentation ->
                instrumentation.requestShadowFieldExecution(
                    parameters,
                    getState(instrumentation, state),
                )
            }
        )
    }

    private val beginFieldFetchInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithBeginFieldFetch>()
    }

    private val beginFieldFetchingInstrumentations by lazy {
        linkedInstrumentations
            .filter { it.key is IViaductInstrumentation.WithBeginFieldFetching || it.key is IViaductInstrumentation.WithBeginFieldFetch }
            .values
    }

    @Suppress("DEPRECATION")
    @Deprecated("deprecated")
    override fun beginFieldFetch(
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any> =
        chainContexts(beginFieldFetchInstrumentations) { instr ->
            instr.beginFieldFetch(parameters, getState(instr, state))
        }

    override fun beginFieldFetching(
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): FieldFetchingInstrumentationContext? =
        chainFieldFetchingContexts(beginFieldFetchingInstrumentations) { instr ->
            instr.beginFieldFetching(parameters, getState(instr, state))
        }

    private val beginFieldCompleteInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithBeginFieldCompletion>()
    }

    override fun beginFieldCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        chainContexts(beginFieldCompleteInstrumentations) { instr ->
            instr.beginFieldCompletion(parameters, getState(instr, state))
        }

    private val beginFieldListCompleteInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithBeginFieldListCompletion>()
    }

    override fun beginFieldListCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        chainContexts(beginFieldListCompleteInstrumentations) { instr ->
            instr.beginFieldListCompletion(parameters, getState(instr, state))
        }

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
        chainContexts(beginFetchObjectInstrumentations) { instr ->
            instr.beginFetchObject(parameters, getState(instr, state))
        }

    private val beginCompleteObjectInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithBeginCompleteObject>()
    }

    override fun beginCompleteObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any> =
        chainContexts(beginCompleteObjectInstrumentations) { instr ->
            instr.beginCompleteObject(parameters, getState(instr, state))
        }

    private val instrumentAccessCheckInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithInstrumentAccessCheck>()
    }

    override fun instrumentAccessCheck(
        checkerExecutor: CheckerExecutor,
        dataFetchingEnvironment: DataFetchingEnvironment,
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): CheckerExecutor {
        var instrumentedChecker = checkerExecutor
        for (instr in instrumentAccessCheckInstrumentations) {
            instrumentedChecker = instr.instrumentAccessCheck(instrumentedChecker, dataFetchingEnvironment, parameters, getState(instr, state))
        }

        return instrumentedChecker
    }

    private val beginNodeFetchingInstrumentations by lazy {
        mapInstrumentations<IViaductInstrumentation.WithBeginNodeFetching>()
    }

    override fun beginNodeFetching(
        parameters: InstrumentNodeFetchingParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        chainContexts(beginNodeFetchingInstrumentations) { instr ->
            instr.beginNodeFetching(parameters, getState(instr, state))
        }

    private inline fun <T : Any> chainContexts(
        instrumentations: Collection<ViaductModernGJInstrumentation>,
        begin: (ViaductModernGJInstrumentation) -> InstrumentationContext<T>?
    ): InstrumentationContext<T> {
        var first: InstrumentationContext<T>? = null
        var all: ArrayList<InstrumentationContext<T>>? = null
        for (instr in instrumentations) {
            val context = begin(instr)
            if (context == null || context === noOp<T>()) continue
            if (first == null) {
                first = context
            } else {
                if (all == null) all = ArrayList<InstrumentationContext<T>>(instrumentations.size).also { it.add(first) }
                all.add(context)
            }
        }
        return when {
            all != null -> ChainedInstrumentationContext(all)
            first != null -> first
            else -> noOp()
        }
    }

    private inline fun chainFieldFetchingContexts(
        instrumentations: Collection<ViaductModernGJInstrumentation>,
        begin: (ViaductModernGJInstrumentation) -> FieldFetchingInstrumentationContext?
    ): FieldFetchingInstrumentationContext {
        var first: FieldFetchingInstrumentationContext? = null
        var all: ArrayList<FieldFetchingInstrumentationContext>? = null
        for (instr in instrumentations) {
            val context = begin(instr)
            if (context == null || context === FieldFetchingInstrumentationContext.NOOP) continue
            if (first == null) {
                first = context
            } else {
                if (all == null) all = ArrayList<FieldFetchingInstrumentationContext>(instrumentations.size).also { it.add(first) }
                all.add(context)
            }
        }
        return when {
            all != null -> ChainedFieldFetchingInstrumentationContext(all)
            first != null -> first
            else -> FieldFetchingInstrumentationContext.NOOP
        }
    }

    private inline fun <reified T : IViaductInstrumentation> mapInstrumentations() =
        linkedInstrumentations
            .filter { it.key is T }
            .values
}
