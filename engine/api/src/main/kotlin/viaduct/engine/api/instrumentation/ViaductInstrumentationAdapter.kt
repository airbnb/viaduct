package viaduct.engine.api.instrumentation

import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.execution.ExecutionContext
import graphql.execution.instrumentation.DocumentAndVariables
import graphql.execution.instrumentation.ExecutionStrategyInstrumentationContext
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext.noOp
import graphql.execution.instrumentation.SimplePerformantInstrumentation
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
import viaduct.engine.api.CheckerExecutor

/**
 * Adapts an optimized ViaductInstrumentation into a standard Instrumentation.
 * See also `asStandardInstrumentation`/`asStandardInstrumentations` in ViaductInstrumentation.
 */
open class ViaductInstrumentationAdapter(
    private val viaductInstrumentation: IViaductInstrumentation
) : ViaductModernGJInstrumentation {
    companion object {
        val default: Instrumentation = SimplePerformantInstrumentation.INSTANCE
    }

    override fun createStateAsync(parameters: InstrumentationCreateStateParameters): CompletableFuture<InstrumentationState?>? =
        CompletableFuture.completedFuture(viaductInstrumentation.createState(parameters))

    override fun beginExecution(
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): InstrumentationContext<ExecutionResult>? = viaductInstrumentation.beginExecution(parameters, state)

    override fun beginParse(
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Document?>? = viaductInstrumentation.beginParse(parameters, state)

    override fun beginValidation(
        parameters: InstrumentationValidationParameters,
        state: InstrumentationState?
    ): InstrumentationContext<MutableList<ValidationError>>? = viaductInstrumentation.beginValidation(parameters, state)

    override fun beginExecuteOperation(
        parameters: InstrumentationExecuteOperationParameters,
        state: InstrumentationState?
    ): InstrumentationContext<ExecutionResult>? = viaductInstrumentation.beginExecuteOperation(parameters, state)

    override fun beginExecutionStrategy(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): ExecutionStrategyInstrumentationContext? = viaductInstrumentation.beginExecutionStrategy(parameters, state)

    override fun beginSubscribedFieldEvent(
        parameters: InstrumentationFieldParameters,
        state: InstrumentationState?
    ): InstrumentationContext<ExecutionResult>? = viaductInstrumentation.beginSubscribedFieldEvent(parameters, state)

    override fun beginFieldExecution(
        parameters: InstrumentationFieldParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        if (viaductInstrumentation is IViaductInstrumentation.WithBeginFieldExecution) {
            viaductInstrumentation.beginFieldExecution(parameters, state)
        } else {
            default.beginFieldExecution(parameters, state)
        }

    @Deprecated("deprecated")
    override fun beginFieldFetch(
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        if (viaductInstrumentation is IViaductInstrumentation.WithBeginFieldFetch) {
            viaductInstrumentation.beginFieldFetch(parameters, state)
        } else {
            @Suppress("DEPRECATION")
            default.beginFieldFetch(parameters, state)
        }

    override fun beginFieldCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        if (viaductInstrumentation is IViaductInstrumentation.WithBeginFieldCompletion) {
            viaductInstrumentation.beginFieldCompletion(parameters, state)
        } else {
            default.beginFieldCompletion(parameters, state)
        }

    override fun beginFieldListCompletion(
        parameters: InstrumentationFieldCompleteParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any>? =
        if (viaductInstrumentation is IViaductInstrumentation.WithBeginFieldListCompletion) {
            viaductInstrumentation.beginFieldListCompletion(parameters, state)
        } else {
            default.beginFieldListCompletion(parameters, state)
        }

    override fun instrumentExecutionInput(
        executionInput: ExecutionInput,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): ExecutionInput = viaductInstrumentation.instrumentExecutionInput(executionInput, parameters, state)

    override fun instrumentDocumentAndVariables(
        documentAndVariables: DocumentAndVariables,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): DocumentAndVariables = viaductInstrumentation.instrumentDocumentAndVariables(documentAndVariables, parameters, state)

    override fun instrumentSchema(
        schema: GraphQLSchema,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): GraphQLSchema = viaductInstrumentation.instrumentSchema(schema, parameters, state)

    override fun instrumentExecutionContext(
        executionContext: ExecutionContext,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): ExecutionContext = viaductInstrumentation.instrumentExecutionContext(executionContext, parameters, state)

    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?
    ): DataFetcher<*> =
        if (viaductInstrumentation is IViaductInstrumentation.WithInstrumentDataFetcher) {
            viaductInstrumentation.instrumentDataFetcher(dataFetcher, parameters, state)
        } else {
            default.instrumentDataFetcher(dataFetcher, parameters, state)
        }

    override fun instrumentExecutionResult(
        executionResult: ExecutionResult,
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?
    ): CompletableFuture<ExecutionResult> = viaductInstrumentation.instrumentExecutionResult(executionResult, parameters, state)

    override fun beginFetchObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Unit> =
        if (viaductInstrumentation is IViaductInstrumentation.WithBeginFetchObject) {
            viaductInstrumentation.beginFetchObject(parameters, state)
        } else {
            noOp()
        }

    override fun beginCompleteObject(
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): InstrumentationContext<Any> =
        if (viaductInstrumentation is IViaductInstrumentation.WithBeginCompleteObject) {
            viaductInstrumentation.beginCompleteObject(parameters, state)
        } else {
            noOp()
        }

    override fun instrumentAccessCheck(
        checkerExecutor: CheckerExecutor,
        parameters: InstrumentationExecutionStrategyParameters,
        state: InstrumentationState?
    ): CheckerExecutor {
        return if (viaductInstrumentation is IViaductInstrumentation.WithInstrumentAccessCheck) {
            return viaductInstrumentation.instrumentAccessCheck(checkerExecutor, parameters, state)
        } else {
            checkerExecutor
        }
    }
}
