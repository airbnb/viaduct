package viaduct.engine.runtime.instrumentation

import graphql.ExecutionInput
import graphql.execution.instrumentation.DocumentAndVariables
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationCreateStateParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.execution.instrumentation.parameters.InstrumentationValidationParameters
import graphql.schema.DataFetcher
import graphql.schema.GraphQLSchema
import java.util.concurrent.CompletableFuture
import viaduct.engine.api.instrumentation.ViaductInstrumentationAdapter
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase

/**
 * [ConditionallyEnabledInstrumentation] allows for a given instrumentation to implement the abstract
 * [isInstrumentationEnabled] function in order to efficiently enable/disable an instrumentation for a given request.
 * This can leverage Flagger, Sitar, or any other mechanism for deciding whether or not an instrumentation should run.
 *
 * The execution of the [isInstrumentationEnabled] method will be cached for the duration of the GraphQL execution. This
 * ensures that any expensive work in this method only runs once, instead of, for instance, on every field execution.
 */
abstract class ConditionallyEnabledInstrumentation : ViaductInstrumentationBase() {
    /**
     * Returns a boolean representing whether or not the instrumentation is enabled for this request. The result of
     * this method is cached for the duration of the GraphQL execution.
     */
    abstract fun isInstrumentationEnabled(executionInput: ExecutionInput): Boolean

    override val asStandardInstrumentation by lazy { ConditionallyEnabledInstrumentationAdapter(this) }
}

// ─────────────────────────────────────────────────────────────
//  State wrapper
// ─────────────────────────────────────────────────────────────
private data class ConditionalState(
    val enabled: Boolean,
    val delegate: InstrumentationState?
) : InstrumentationState

// Small helper to reduce boilerplate
private inline fun <T> InstrumentationState?.withConditional(fn: (enabled: Boolean, delegate: InstrumentationState?) -> T): T {
    val c = this as? ConditionalState
    return fn(c?.enabled == true, c?.delegate)
}

/**
 * Adapts a [ConditionallyEnabledInstrumentation] to a standard GraphQL-Java instrumentation.
 */
class ConditionallyEnabledInstrumentationAdapter(
    private val instrumentation: ConditionallyEnabledInstrumentation
) : ViaductInstrumentationAdapter(instrumentation) {
    override fun createStateAsync(parameters: InstrumentationCreateStateParameters): CompletableFuture<InstrumentationState?>? {
        val enabled = instrumentation.isInstrumentationEnabled(parameters.executionInput)
        val innerFuture = if (enabled) {
            super.createStateAsync(parameters)
        } else {
            default.createStateAsync(parameters)
        }
        val safeFuture = innerFuture ?: CompletableFuture.completedFuture<InstrumentationState?>(null)
        return safeFuture?.thenApply { inner -> ConditionalState(enabled, inner) }
    }

    // 2 ─ Dispatch helper used by every override
    private inline fun <T> dispatch(
        state: InstrumentationState?,
        whenEnabled: (InstrumentationState?) -> T,
        whenDisabled: (InstrumentationState?) -> T
    ): T =
        state.withConditional { enabled, delegate ->
            if (enabled) whenEnabled(delegate) else whenDisabled(delegate)
        }

    override fun beginExecution(
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.beginExecution(parameters, it) },
        { default.beginExecution(parameters, it) }
    )

    override fun beginParse(
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.beginParse(parameters, it) },
        { default.beginParse(parameters, it) }
    )

    override fun beginValidation(
        parameters: InstrumentationValidationParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.beginValidation(parameters, it) },
        { default.beginValidation(parameters, it) }
    )

    override fun beginExecuteOperation(
        parameters: InstrumentationExecuteOperationParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.beginExecuteOperation(parameters, it) },
        { default.beginExecuteOperation(parameters, it) }
    )

    override fun beginExecutionStrategy(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.beginExecutionStrategy(parameters, it) },
        { default.beginExecutionStrategy(parameters, it) }
    )

    override fun beginSubscribedFieldEvent(
        parameters: InstrumentationFieldParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.beginSubscribedFieldEvent(parameters, it) },
        { default.beginSubscribedFieldEvent(parameters, it) }
    )

    override fun instrumentExecutionInput(
        executionInput: ExecutionInput,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.instrumentExecutionInput(executionInput, parameters, it) },
        { default.instrumentExecutionInput(executionInput, parameters, it) }
    )

    override fun instrumentDocumentAndVariables(
        documentAndVariables: DocumentAndVariables,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.instrumentDocumentAndVariables(documentAndVariables, parameters, it) },
        { default.instrumentDocumentAndVariables(documentAndVariables, parameters, it) }
    )

    override fun instrumentSchema(
        schema: GraphQLSchema,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.instrumentSchema(schema, parameters, it) },
        { default.instrumentSchema(schema, parameters, it) }
    )

    override fun instrumentExecutionContext(
        executionContext: graphql.execution.ExecutionContext,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.instrumentExecutionContext(executionContext, parameters, it) },
        { default.instrumentExecutionContext(executionContext, parameters, it) }
    )

    override fun instrumentExecutionResult(
        executionResult: graphql.ExecutionResult,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.instrumentExecutionResult(executionResult, parameters, it) },
        { default.instrumentExecutionResult(executionResult, parameters, it) }
    )

    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.instrumentDataFetcher(dataFetcher, parameters, it) },
        { default.instrumentDataFetcher(dataFetcher, parameters, it) }
    )

    override fun beginFieldExecution(
        parameters: InstrumentationFieldParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.beginFieldExecution(parameters, it) },
        { default.beginFieldExecution(parameters, it) }
    )

    @Suppress("DEPRECATION")
    @Deprecated("deprecated")
    override fun beginFieldFetch(
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.beginFieldFetch(parameters, it) },
        { default.beginFieldFetch(parameters, it) }
    )

    override fun beginFieldCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.beginFieldCompletion(parameters, it) },
        { default.beginFieldCompletion(parameters, it) }
    )

    override fun beginFieldListCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState?
    ) = dispatch(
        state,
        { super.beginFieldListCompletion(parameters, it) },
        { default.beginFieldListCompletion(parameters, it) }
    )
}
