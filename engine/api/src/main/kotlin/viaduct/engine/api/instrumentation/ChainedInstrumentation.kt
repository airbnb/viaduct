package viaduct.engine.api.instrumentation

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.PublicApi
import graphql.execution.Async
import graphql.execution.ExecutionContext
import graphql.execution.FieldValueInfo
import graphql.execution.instrumentation.DocumentAndVariables
import graphql.execution.instrumentation.ExecutionStrategyInstrumentationContext
import graphql.execution.instrumentation.FieldFetchingInstrumentationContext
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationCreateStateParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.execution.instrumentation.parameters.InstrumentationValidationParameters
import graphql.language.Document
import graphql.schema.DataFetcher
import graphql.schema.GraphQLSchema
import graphql.validation.ValidationError
import java.util.concurrent.CompletableFuture

/**
 * EDITORS NOTE: This is a fork of [graphql.execution.instrumentation.ChainedInstrumentation], rewritten in Kotlin. The
 * main value of rewriting this in Kotlin is because this class is called in the hot path -- we may run 1000's of data
 * fetchers have thousands of fields referenced in a given query, and thus the code here will be executed a TON.
 *
 * When profiling Viaduct, we discovered 1-3% of active CPU time being spent in ChainedMetricInstrumentation. This
 * was mostly due to the use of ".stream()" everywhere, which, in the hot path, has a non-trivial amount of overhead.
 * Rewriting this in Kotlin, we use the Kotlin stdlib `forEach`, which is much, much more efficient than `.stream()`.
 * After deploying this, methods in this class no longer show up on the CPU trace.
 *
 * This is a micro-optimization, but gotta keep things fast! Also, it's unlikely that this class will change a ton
 * between GraphQL-Java versions, unless they completely rewrite their instrumentation pipeline (which, if they did,
 * would require a ton of changes in Viaduct especially). Therefore, this should be a pretty safe fork.
 *
 * ============ ORIGINAL COMMENT BELOW ===============
 *
 * This allows you to chain together a number of [graphql.execution.instrumentation.Instrumentation] implementations
 * and run them in sequence.  The list order of instrumentation objects is always guaranteed to be followed and
 * the [graphql.execution.instrumentation.InstrumentationState] objects they create will be passed back to the
 * originating implementation.
 *
 * @see graphql.execution.instrumentation.Instrumentation
 */
@PublicApi
open class ChainedInstrumentation(
    @Suppress("MemberVisibilityCanBePrivate")
    val instrumentations: List<Instrumentation>
) : Instrumentation {
    protected fun getState(
        instrumentation: Instrumentation,
        chainedState: InstrumentationState?
    ): InstrumentationState? = (chainedState as? ChainedInstrumentationState)?.getState(instrumentation)

    override fun createStateAsync(parameters: InstrumentationCreateStateParameters): CompletableFuture<InstrumentationState>? {
        return Async.each<Instrumentation, Pair<Instrumentation, InstrumentationState?>>(instrumentations) { instr ->
            val fut = instr.createStateAsync(parameters) ?: CompletableFuture.completedFuture(null)
            fut.thenApply { instr to it }
        }.thenApply { pairs ->
            ChainedInstrumentationState(pairs.toMap())
        }
    }

    override fun beginExecution(
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): InstrumentationContext<ExecutionResult> =
        ChainedInstrumentationContext(
            instrumentations.map { instr ->
                instr.beginExecution(parameters, getState(instr, state))
            }
        )

    override fun beginParse(
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Document>? =
        ChainedInstrumentationContext(
            instrumentations.map { instr ->
                instr.beginParse(parameters, getState(instr, state))
            }
        )

    override fun beginValidation(
        parameters: InstrumentationValidationParameters,
        state: InstrumentationState?
    ): InstrumentationContext<List<ValidationError>> =
        ChainedInstrumentationContext(
            instrumentations.map { instr ->
                instr.beginValidation(parameters, getState(instr, state))
            }
        )

    override fun beginExecuteOperation(
        parameters: InstrumentationExecuteOperationParameters,
        state: InstrumentationState?
    ): InstrumentationContext<ExecutionResult> =
        ChainedInstrumentationContext(
            instrumentations.map { instr ->
                instr.beginExecuteOperation(parameters, getState(instr, state))
            }
        )

    override fun beginExecutionStrategy(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): ExecutionStrategyInstrumentationContext? =
        ChainedExecutionStrategyInstrumentationContext(
            instrumentations.mapNotNull { instr ->
                instr.beginExecutionStrategy(parameters, getState(instr, state))
            }
        )

    override fun beginFieldExecution(
        parameters: InstrumentationFieldParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        ChainedInstrumentationContext(
            instrumentations.map { instr ->
                instr.beginFieldExecution(parameters, getState(instr, state))
            }
        )

    override fun beginDeferredField(
        parameters: InstrumentationFieldParameters?,
        state: InstrumentationState?
    ): InstrumentationContext<Any> =
        ChainedInstrumentationContext(
            instrumentations.map { instr ->
                instr.beginDeferredField(parameters, getState(instr, state))
            }
        )

    override fun beginFieldFetching(
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): FieldFetchingInstrumentationContext? =
        ChainedFieldFetchingInstrumentationContext(
            instrumentations.mapNotNull { instr ->
                instr.beginFieldFetching(parameters, getState(instr, state))
            }
        )

    @Deprecated("deprecated")
    override fun beginFieldFetch(
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        ChainedInstrumentationContext(
            instrumentations.map { instr ->
                @Suppress("DEPRECATION")
                instr.beginFieldFetch(parameters, getState(instr, state))
            }
        )

    override fun beginFieldCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        ChainedInstrumentationContext(
            instrumentations.map { instr ->
                instr.beginFieldCompletion(parameters, getState(instr, state))
            }
        )

    override fun beginFieldListCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        ChainedInstrumentationContext(
            instrumentations.map { instr ->
                instr.beginFieldListCompletion(parameters, getState(instr, state))
            }
        )

    override fun instrumentExecutionInput(
        executionInput: ExecutionInput,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): ExecutionInput {
        var instrumentedExecutionInput = executionInput
        for (instr in instrumentations) {
            instrumentedExecutionInput =
                instr.instrumentExecutionInput(
                    instrumentedExecutionInput,
                    parameters,
                    getState(instr, state)
                )
        }
        return instrumentedExecutionInput
    }

    override fun instrumentDocumentAndVariables(
        documentAndVariables: DocumentAndVariables,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): DocumentAndVariables {
        var instrumentedDocumentAndVariables = documentAndVariables
        for (instr in instrumentations) {
            instrumentedDocumentAndVariables =
                instr.instrumentDocumentAndVariables(
                    instrumentedDocumentAndVariables,
                    parameters,
                    getState(instr, state)
                )
        }
        return instrumentedDocumentAndVariables
    }

    override fun instrumentSchema(
        schema: GraphQLSchema,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): GraphQLSchema {
        var instrumentedSchema = schema
        for (instr in instrumentations) {
            instrumentedSchema =
                instr.instrumentSchema(
                    instrumentedSchema,
                    parameters,
                    getState(instr, state)
                )
        }
        return instrumentedSchema
    }

    override fun instrumentExecutionContext(
        executionContext: ExecutionContext,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): ExecutionContext {
        var instrumentedExecutionContext = executionContext
        for (instr in instrumentations) {
            instrumentedExecutionContext =
                instr.instrumentExecutionContext(
                    instrumentedExecutionContext,
                    parameters,
                    getState(instr, state)
                )
        }
        return instrumentedExecutionContext
    }

    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): DataFetcher<*> {
        var instrumentedDataFetcher = dataFetcher
        for (instr in instrumentations) {
            instrumentedDataFetcher =
                instr.instrumentDataFetcher(
                    instrumentedDataFetcher,
                    parameters,
                    getState(instr, state)
                )
        }
        return instrumentedDataFetcher
    }

    override fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): CompletableFuture<ExecutionResult> {
        val resultsFuture =
            Async.eachSequentially(instrumentations) { instr: Instrumentation,
                                                       prevResults: List<ExecutionResult?> ->
                val lastResult =
                    if (prevResults.isNotEmpty()) {
                        prevResults[prevResults.size - 1]
                    } else {
                        executionResult
                    }
                instr.instrumentExecutionResult(
                    lastResult,
                    parameters,
                    getState(instr, state)
                )
            }
        return resultsFuture.thenApply { results: List<ExecutionResult?> ->
            if (results.isEmpty()) executionResult else results[results.size - 1]
        }
    }

    class ChainedInstrumentationState internal constructor(
        private val instrumentationStates: Map<Instrumentation, InstrumentationState?>
    ) : InstrumentationState {
        fun getState(instrumentation: Instrumentation): InstrumentationState? {
            return instrumentationStates[instrumentation]
        }
    }

    protected class ChainedInstrumentationContext<T : Any?>(
        private val contexts: List<InstrumentationContext<T>?>
    ) : InstrumentationContext<T> {
        override fun onDispatched() {
            contexts.forEach { context: InstrumentationContext<T>? -> context?.onDispatched() }
        }

        override fun onCompleted(
            result: T?,
            t: Throwable?
        ) {
            contexts.forEach { context: InstrumentationContext<T>? -> context?.onCompleted(result, t) }
        }
    }

    protected class ChainedExecutionStrategyInstrumentationContext(
        val contexts: List<ExecutionStrategyInstrumentationContext>
    ) : ExecutionStrategyInstrumentationContext {
        override fun onDispatched() {
            contexts.forEach { context: ExecutionStrategyInstrumentationContext -> context.onDispatched() }
        }

        override fun onCompleted(
            result: ExecutionResult?,
            t: Throwable?
        ) {
            contexts.forEach { context: ExecutionStrategyInstrumentationContext -> context.onCompleted(result, t) }
        }

        override fun onFieldValuesInfo(fieldValueInfoList: List<FieldValueInfo>) {
            contexts.forEach { context: ExecutionStrategyInstrumentationContext ->
                context.onFieldValuesInfo(fieldValueInfoList)
            }
        }
    }

    protected class ChainedFieldFetchingInstrumentationContext(
        val contexts: List<FieldFetchingInstrumentationContext>
    ) : FieldFetchingInstrumentationContext {
        override fun onDispatched() {
            contexts.forEach { context -> context.onDispatched() }
        }

        override fun onCompleted(
            result: Any?,
            t: Throwable?
        ) {
            contexts.forEach { context -> context.onCompleted(result, t) }
        }

        override fun onFetchedValue(fetchedValue: Any?) {
            contexts.forEach { context -> context.onFetchedValue(fetchedValue) }
        }
    }
}
